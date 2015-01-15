/*
 * Apache HTTPD logparsing made easy
 * Copyright (C) 2011-2015 Niels Basjes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.basjes.parse.apachehttpdlog;


import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import nl.basjes.hadoop.input.ParsedRecord;
import nl.basjes.parse.core.Casts;
import nl.basjes.parse.core.Dissector;

import nl.basjes.parse.core.exceptions.DissectionFailure;
import nl.basjes.parse.core.exceptions.InvalidDissectorException;
import nl.basjes.parse.core.exceptions.MissingDissectorsException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.AbstractDeserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static nl.basjes.parse.core.Casts.DOUBLE;
import static nl.basjes.parse.core.Casts.LONG;
import static nl.basjes.parse.core.Casts.STRING;
import static org.apache.hadoop.hive.serde.serdeConstants.BIGINT_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.DOUBLE_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.STRING_TYPE_NAME;

/**
 * Hive SerDe for accessing Apache Access log files.
 * An example DDL statement
 * would be:
 * <pre>
 *
 * ADD JAR target/httpdlog-serde-1.7-SNAPSHOT-job.jar;
 * CREATE EXTERNAL TABLE nbasjes.clicks (
 *       ip           STRING
 *      ,timestamp    BIGINT
 *      ,useragent    STRING
 *      ,referrer     STRING
 *      ,bui          STRING
 *      ,screenHeight BIGINT
 *      ,screenWidth  BIGINT
 *      )
 * ROW FORMAT SERDE 'nl.basjes.parse.apachehttpdlog.ApacheHttpdlogDeserializer'
 * WITH SERDEPROPERTIES (
 *       "logformat"       = "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\" \"%{Cookie}i\" %T %V"
 *      ,"map:request.firstline.uri.query.g" = "HTTP.URI"
 *      ,"map:request.firstline.uri.query.r" = "HTTP.URI"
 *
 *      ,"field:timestamp" = "TIME.EPOCH:request.receive.time.epoch"
 *      ,"field:ip"        = "IP:connection.client.host"
 *      ,"field:useragent" = "HTTP.USERAGENT:request.user-agent"
 *
 *      ,"field:referrer"  = "STRING:request.firstline.uri.query.g.query.referrer"
 *      ,"field:bui"       = "HTTP.COOKIE:request.cookies.bui"
 *
 *      ,"load:nl.basjes.parse.dissectors.http.ScreenResolutionDissector" = "x"
 *      ,"map:request.firstline.uri.query.s" = "SCREENRESOLUTION"
 *      ,"field:screenHeight" = "SCREENHEIGHT:request.firstline.uri.query.s.height"
 *      ,"field:screenWidth"  = "SCREENWIDTH:request.firstline.uri.query.s.width"
 *      )
 * STORED AS TEXTFILE
 * LOCATION "/user/nbasjes/clicks";
 * </pre>
*/

//@SerDeSpec(schemaProps = {
//    serdeConstants.LIST_COLUMNS, serdeConstants.LIST_COLUMN_TYPES,
//    RegexSerDe.INPUT_REGEX, RegexSerDe.OUTPUT_FORMAT_STRING,
//    RegexSerDe.INPUT_REGEX_CASE_SENSITIVE
//})
public class ApacheHttpdlogDeserializer extends AbstractDeserializer {
    private static final Logger      LOG = LoggerFactory.getLogger(ApacheHttpdlogDeserializer.class);
    private static final String      FIELD = "field:";

    private static final String      MAP_FIELD = "map:";
    private static final int         MAP_FIELD_LENGTH = MAP_FIELD.length();
    private static final String      LOAD_DISSECTOR = "load:";
    private static final int         LOAD_DISSECTOR_LENGTH = LOAD_DISSECTOR.length();

    private StructObjectInspector   rowOI;
    private ArrayList<Object>       row;

    private ApacheHttpdLoglineParser<ParsedRecord> parser;
    private ParsedRecord            currentValue;


    // We do not want the parsing to fail immediately when we hit a single 'bad' line.
    // So we count the good and bad lines.
    // If we see more than 1% bad lines we abort (after we have seen 1000 lines)
    private static final long    MINIMAL_FAIL_LINES      = 1000;
    private static final int     MINIMAL_FAIL_PERCENTAGE =    1;
    private long    bytesInput  = 0;
    private long    linesInput  = 0;
    private long    linesBad    = 0;

    class ColumnToGetterMapping {
        int    index;
        Casts  casts;
        String fieldValue;
    }

    private List<ColumnToGetterMapping> columnToGetterMappings = new ArrayList<>();

    @Override
    public void initialize(Configuration conf, Properties props)
        throws SerDeException {

        boolean usable = true;
        bytesInput = 0;
        linesInput = 0;
        linesBad   = 0;

        String logformat = props.getProperty("logformat");

        Map<String, Set<String>> typeRemappings = new HashMap<>();
        List<Dissector> additionalDissectors = new ArrayList<>();

        for (Map.Entry<Object, Object> property: props.entrySet()){
            String key = (String)property.getKey();

            if (key.startsWith(MAP_FIELD)) {
                String mapField = key.substring(MAP_FIELD_LENGTH);
                String mapType  = (String)property.getValue();

                Set<String> remapping = typeRemappings.get(mapField);
                if (remapping == null) {
                    remapping = new HashSet<>();
                    typeRemappings.put(mapField, remapping);
                }
                remapping.add(mapType);
                LOG.info("Add mapping for field \"{}\" to type \"{}\"", mapField, mapType);
                continue;
            }

            if (key.startsWith(LOAD_DISSECTOR)) {
                String dissectorClassName = key.substring(LOAD_DISSECTOR_LENGTH);
                String dissectorParam = (String)property.getValue();

                try {
                    Class<?> clazz = Class.forName(dissectorClassName);
                    Constructor<?> constructor = clazz.getConstructor();
                    Dissector instance = (Dissector) constructor.newInstance();
                    instance.initializeFromSettingsParameter(dissectorParam);
                    additionalDissectors.add(instance);
                } catch (ClassNotFoundException e) {
                    throw new SerDeException("Found load with bad specification: No such class:" + dissectorClassName, e);
                } catch (NoSuchMethodException e) {
                    throw new SerDeException("Found load with bad specification: Class does not have the required constructor",e);
                } catch (InvocationTargetException e) {
                    throw new SerDeException("Got an InvocationTargetException",e);
                } catch (InstantiationException e) {
                    throw new SerDeException("Got an InstantiationException",e);
                } catch (IllegalAccessException e) {
                    throw new SerDeException("Found load with bad specification: Required constructor is not public",e);
                }
                LOG.debug("Loaded additional dissector: {}(\"{}\")", dissectorClassName, dissectorParam);
            }
        }

        currentValue = new ParsedRecord();


//        List<String>            fieldList;
        int                     numColumns;

        String columnNameProperty  = props.getProperty(serdeConstants.LIST_COLUMNS);
        String columnTypeProperty  = props.getProperty(serdeConstants.LIST_COLUMN_TYPES);
        List<String> columnNames   = Arrays.asList(columnNameProperty.split(","));
        List<TypeInfo> columnTypes = TypeInfoUtils.getTypeInfosFromTypeString(columnTypeProperty);
        assert columnNames.size() == columnTypes.size();
        numColumns = columnNames.size();

        parser = new ApacheHttpdLoglineParser<>(ParsedRecord.class, logformat);
        parser.setTypeRemappings(typeRemappings);
        parser.addDissectors(additionalDissectors);

        List<ObjectInspector> columnOIs = new ArrayList<>(columnNames.size());

        try {
            for (int columnNr = 0; columnNr < numColumns; columnNr++) {
                columnOIs.add(TypeInfoUtils.getStandardJavaObjectInspectorFromTypeInfo(columnTypes.get(columnNr)));
                String columnName = columnNames.get(columnNr);
                TypeInfo columnType = columnTypes.get(columnNr);

                String fieldValue = props.getProperty(FIELD + columnName);

                if (fieldValue == null) {
                    LOG.error("MUST have Field value for column \"{}\".", columnName);
                    usable = false;
                    continue;
                }

                ColumnToGetterMapping ctgm = new ColumnToGetterMapping();
                ctgm.index      = columnNr;
                ctgm.fieldValue = fieldValue;

                List<String> singleFieldValue= new ArrayList<>();
                singleFieldValue.add(fieldValue);
                switch (columnType.getTypeName()) {
                    case STRING_TYPE_NAME:
                        ctgm.casts = STRING;
                        parser.addParseTarget(ParsedRecord.class.getMethod("set", String.class, String.class), singleFieldValue);
                        break;
                    case BIGINT_TYPE_NAME:
                        ctgm.casts = LONG;
                        parser.addParseTarget(ParsedRecord.class.getMethod("set", String.class, Long.class), singleFieldValue);
                        break;
                    case DOUBLE_TYPE_NAME:
                        ctgm.casts = DOUBLE;
                        parser.addParseTarget(ParsedRecord.class.getMethod("set", String.class, Double.class), singleFieldValue);
                        break;
                    default:
                        LOG.error("Requested column type {} is not supported at this time.", columnType.getTypeName());
                        usable = false;
                        break;
                }
                columnToGetterMappings.add(ctgm);
            }
        } catch (NoSuchMethodException
                |SecurityException e) {
            throw new SerDeException("(Should not occur) Caught exception: {}", e);
        }

        // StandardStruct uses ArrayList to store the row.
        rowOI = ObjectInspectorFactory.getStandardStructObjectInspector(columnNames, columnOIs);

        // Constructing the row object, etc, which will be reused for all rows.
        row = new ArrayList<>(numColumns);
        for (int c = 0; c < numColumns; c++) {
            row.add(null);
        }

        if (!usable) {
            throw new SerDeException("Fatal config error. Check the logged error messages why.");
        }

    }

    @Override
    public ObjectInspector getObjectInspector() throws SerDeException {
        return rowOI;
    }

    @Override
    public Object deserialize(Writable writable) throws SerDeException {
        if (!(writable instanceof Text)) {
            throw new SerDeException("The input MUST be a Text line.");
        }

        bytesInput += ((Text) writable).getLength();
        linesInput ++;

        try {
            currentValue.clear();
            parser.parse(currentValue, writable.toString());
        } catch (DissectionFailure dissectionFailure) {
            linesBad ++;
            if (linesInput >= MINIMAL_FAIL_LINES) {
                if (100* linesBad > MINIMAL_FAIL_PERCENTAGE * linesInput){
                    throw new SerDeException("To many bad lines: " + linesBad + " of " + linesInput + " are bad.");
                }
            }
            return null; // Just return that this line is nothing.
        } catch (InvalidDissectorException |MissingDissectorsException e) {
            throw new SerDeException("Cannot continue; Fix the Dissectors before retrying",e);
        }

        for (ColumnToGetterMapping ctgm: columnToGetterMappings) {
            switch (ctgm.casts) {
                case STRING:
                    String currentValueString = currentValue.getString(ctgm.fieldValue);
                    row.set(ctgm.index, currentValueString);
                    break;
                case LONG:
                    Long currentValueLong = currentValue.getLong(ctgm.fieldValue);
                    row.set(ctgm.index, currentValueLong);
                    break;
                case DOUBLE:
                    Double currentValueDouble = currentValue.getDouble(ctgm.fieldValue);
                    row.set(ctgm.index, currentValueDouble);
                    break;
            }
        }

        return row;
    }

    @Override
    public SerDeStats getSerDeStats() {
        return new SerDeStats();
    }

}
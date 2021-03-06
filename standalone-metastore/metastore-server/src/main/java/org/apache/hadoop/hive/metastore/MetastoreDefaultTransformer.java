/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore;

import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.ACCESSTYPE_NONE;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.ACCESSTYPE_READONLY;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.ACCESSTYPE_READWRITE;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.ACCESSTYPE_WRITEONLY;

import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetastoreDefaultTransformer implements IMetaStoreMetadataTransformer {
  public static final Logger LOG = LoggerFactory.getLogger(MetastoreDefaultTransformer.class);
  private IHMSHandler hmsHandler = null;

  private static final String CONNECTORREAD = "CONNECTORREAD".intern();
  private static final String CONNECTORWRITE = "CONNECTORWRITE".intern();
  private static final String EXTWRITE = "EXTWRITE".intern();
  private static final String EXTREAD = "EXTREAD".intern();
  private static final String HIVEACIDWRITE = "HIVEACIDWRITE".intern();
  private static final String HIVEBUCKET2 = "HIVEBUCKET2".intern();
  private static final String HIVECACHEINVALIDATE = "HIVECACHEINVALIDATE".intern();
  private static final String HIVEFULLACIDREAD = "HIVEFULLACIDREAD".intern();
  private static final String HIVEFULLACIDWRITE = "HIVEFULLACIDWRITE".intern();
  private static final String HIVEMANAGEDINSERTREAD = "HIVEMANAGEDINSERTREAD".intern();
  private static final String HIVEMANAGEDINSERTWRITE = "HIVEMANAGEDINSERTWRITE".intern();
  private static final String HIVEMANAGESTATS = "HIVEMANAGESTATS".intern();
  private static final String HIVEMQT = "HIVEMQT".intern();
  private static final String HIVEONLYMQTWRITE = "HIVEONLYMQTWRITE".intern();
  private static final String HIVESQL = "HIVESQL".intern();
  private static final String OBJCAPABILITIES = "OBJCAPABILITIES".intern();

  public MetastoreDefaultTransformer(IHMSHandler handler) throws HiveMetaException {
    this.hmsHandler = handler;
  }

  @Override
  public Map<Table, List<String>> transform(List<Table> objects, List<String> processorCapabilities, String processorId) throws MetaException {
    LOG.info("Starting translation for processor " + processorId + " on list " + objects.size());
    Map<Table, List<String>> ret = new HashMap<Table, List<String>>();

    for (Table table : objects) {
      Map<String, String> params = table.getParameters();
      String tableType = table.getTableType();
      String tCapabilities = params.get(OBJCAPABILITIES);
      int numBuckets = table.getSd().getNumBuckets();
      boolean isBucketed = (numBuckets > 0) ? true : false;
      List<String> generated = new ArrayList<String>();

      LOG.info("Table " + table.getTableName() + ",#bucket=" + numBuckets + ",isBucketed:" + isBucketed + ",tableType=" + tableType + ",tableCapabilities=" + tCapabilities);

      // if the table has no tCapabilities, then generate default ones
      if (tCapabilities == null) {
        LOG.debug("Table has no specific required capabilities");

        switch (tableType) {
          case "EXTERNAL_TABLE":
            Table newTable = new Table(table);
            generated.add(EXTREAD);
            generated.add(EXTWRITE);

            if (numBuckets > 0) {
              generated.add(HIVEBUCKET2);
              if (processorCapabilities.contains(HIVEBUCKET2)) {
                newTable.setAccessType(ACCESSTYPE_READWRITE);
              } else {
                newTable.setAccessType(ACCESSTYPE_READONLY);
                StorageDescriptor newSd = new StorageDescriptor(table.getSd());
                newSd.setNumBuckets(-1); // remove bucketing info
                newTable.setSd(newSd);
              }
            } else { // Unbucketed, so RW for all
              newTable.setAccessType(ACCESSTYPE_READWRITE);
            }

            ret.put(newTable, generated);
            break;
          case "MANAGED_TABLE":
            String txnal = params.get("transactional");
            if (txnal == null || txnal.equalsIgnoreCase("FALSE")) { // non-ACID MANAGED table
              table.setAccessType(ACCESSTYPE_READWRITE);
              // table does not require any capabilities for full RW
            }

            if (txnal != null && txnal.equalsIgnoreCase("TRUE")) { // ACID table
              if (!(processorCapabilities.contains(CONNECTORREAD) ||
                  processorCapabilities.contains(HIVEFULLACIDREAD) ||
                  processorCapabilities.contains(HIVEMANAGEDINSERTREAD))) {
                table.setAccessType(ACCESSTYPE_NONE); // clients have no access to ACID tables without capabilities
                generated.add(CONNECTORREAD);
                generated.add(CONNECTORWRITE);
                generated.add(HIVECACHEINVALIDATE);
                generated.add(HIVEMANAGESTATS);
              }

              String txntype = params.get("transactional_properties");
              if (txntype != null && txntype.equalsIgnoreCase("insert_only")) { // MICRO_MANAGED Tables
                generated.add(HIVEMANAGEDINSERTREAD);
                generated.add(HIVEMANAGEDINSERTWRITE);
                // MGD table is insert only, not full ACID
                if (processorCapabilities.contains(HIVEMANAGEDINSERTWRITE) || processorCapabilities.contains(CONNECTORWRITE)) {
                  table.setAccessType(ACCESSTYPE_READWRITE); // clients have RW access to INSERT-ONLY ACID tables
                } else if (processorCapabilities.contains(HIVEMANAGEDINSERTREAD) || processorCapabilities.contains(CONNECTORREAD)) {
                  table.setAccessType(ACCESSTYPE_READONLY); // clients have RO access to INSERT-ONLY ACID tables
                } else {
                  table.setAccessType(ACCESSTYPE_NONE); // clients have NO access to INSERT-ONLY ACID tables
                }
              } else { // FULL ACID MANAGED TABLE
                generated.add(HIVEFULLACIDWRITE);
                generated.add(HIVEFULLACIDREAD);
                if (processorCapabilities.contains(HIVEFULLACIDWRITE) || processorCapabilities.contains(CONNECTORWRITE)) {
                  table.setAccessType(ACCESSTYPE_READWRITE); // clients have RW access to IUD ACID tables
                } else if (processorCapabilities.contains(HIVEFULLACIDREAD) || processorCapabilities.contains(CONNECTORREAD)) {
                  table.setAccessType(ACCESSTYPE_READONLY); // clients have RO access to IUD ACID tables
                } else {
                  table.setAccessType(ACCESSTYPE_NONE); // clients have NO access to IUD ACID tables
                }
              }
            }
            ret.put(table, generated);
            break;
          case "VIRTUAL_VIEW":
            generated.add("HIVESQL");
            if (processorCapabilities.contains(HIVESQL) ||
                processorCapabilities.contains(CONNECTORREAD)) {
              table.setAccessType(ACCESSTYPE_READONLY);
            } else {
              table.setAccessType(ACCESSTYPE_NONE);
            }
            ret.put(table, generated);
            break;
          case "MATERIALIZED_VIEW":
            generated.add(CONNECTORREAD);
            generated.add(HIVEFULLACIDREAD);
            generated.add(HIVEONLYMQTWRITE);
            generated.add(HIVEMANAGESTATS);
            generated.add(HIVEMQT);
            if ((processorCapabilities.contains(CONNECTORREAD) ||
                processorCapabilities.contains(HIVEFULLACIDREAD)) && processorCapabilities.contains(HIVEMQT)) {
              LOG.info("Processor has one of the READ abilities and HIVEMQT, AccessType=RO");
              table.setAccessType(ACCESSTYPE_READONLY);
            } else {
              LOG.info("Processor has no READ abilities or HIVEMQT, AccessType=None");
              table.setAccessType(ACCESSTYPE_NONE);
            }
            ret.put(table, generated);
            break;
          default:
            table.setAccessType(ACCESSTYPE_NONE);
            ret.put(table,generated);
            break;
        }
        continue;
      }

      // WITH CAPABLITIES ON TABLE
      tCapabilities = tCapabilities.replaceAll("\\s","").toUpperCase(); // remove spaces between tCapabilities + toUppercase
      List<String> requiredCapabilities = Arrays.asList(tCapabilities.split(","));
      switch (tableType) {
        case "EXTERNAL_TABLE":
          if (processorCapabilities.containsAll(requiredCapabilities)) {
            // AccessType is RW
            LOG.info("Abilities for match: Table type=" + tableType + ",accesstype is RW");
            table.setAccessType(ACCESSTYPE_READWRITE);
            ret.put(table, requiredCapabilities);
            break;
          }

          Table newTable = new Table(table);
          boolean removedBucketing = false;

          if (requiredCapabilities.contains(HIVEBUCKET2) && !processorCapabilities.contains(HIVEBUCKET2)) {
            StorageDescriptor newSd = new StorageDescriptor(table.getSd());
            newSd.setNumBuckets(-1); // removing bucketing if HIVEBUCKET2 isnt specified
            newTable.setSd(newSd);
            removedBucketing = true;
            newTable.setAccessType(ACCESSTYPE_READONLY);
            LOG.info("Removed bucketing information from table");
          }

          if (requiredCapabilities.contains(EXTWRITE) && processorCapabilities.contains(EXTWRITE)) {
            if (!removedBucketing) {
              LOG.info("EXTWRITE Matches, accessType=" + ACCESSTYPE_READWRITE);
              newTable.setAccessType(ACCESSTYPE_READWRITE);
            }
          } else if (requiredCapabilities.contains(EXTREAD) && processorCapabilities.contains(EXTREAD)) {
            LOG.debug("EXTREAD Matches, accessType=" + ACCESSTYPE_READONLY);
            newTable.setAccessType(ACCESSTYPE_READONLY);
          } else {
            LOG.debug("No matches, accessType=" + ACCESSTYPE_NONE);
            newTable.setAccessType(ACCESSTYPE_NONE);
          }

          LOG.info("setting required to " + requiredCapabilities);
          ret.put(newTable, requiredCapabilities);
          break;
        case "MANAGED_TABLE":
          if (processorCapabilities.containsAll(requiredCapabilities)) {
            // AccessType is RW
            LOG.info("Abilities for match: Table type=" + tableType + ",accesstype is RW");
            table.setAccessType(ACCESSTYPE_READWRITE);
            ret.put(table, requiredCapabilities);
            continue;
          }

          String txnal = params.get("transactional");
          if (txnal == null || txnal.equalsIgnoreCase("FALSE")) { // non-ACID MANAGED table
            LOG.info("Table is non ACID: ,accesstype is RW");
            table.setAccessType(ACCESSTYPE_READWRITE);
            // table does not require any capabilities for full RW
          }

          if (txnal != null && txnal.equalsIgnoreCase("TRUE")) { // ACID table
            String txntype = params.get("transactional_properties");
            if (txntype != null && txntype.equalsIgnoreCase("insert_only")) { // MICRO_MANAGED Tables
              LOG.info("Table is INSERTONLY ACID: ,accesstype is RW");
              // MGD table is insert only, not full ACID
              if ((processorCapabilities.contains(HIVEMANAGEDINSERTWRITE) && requiredCapabilities.contains(HIVEMANAGEDINSERTWRITE))
                   || (processorCapabilities.contains(CONNECTORWRITE) && requiredCapabilities.contains(CONNECTORWRITE))) {
                table.setAccessType(ACCESSTYPE_READWRITE); // clients have RW access to INSERT-ONLY ACID tables
              } else if ((processorCapabilities.contains(HIVEMANAGEDINSERTREAD) && requiredCapabilities.contains(HIVEMANAGEDINSERTREAD))
                   || (processorCapabilities.contains(CONNECTORREAD) && requiredCapabilities.contains(CONNECTORREAD))) {
                table.setAccessType(ACCESSTYPE_READONLY);
              } else {
                table.setAccessType(ACCESSTYPE_NONE);
              }
            } else { // MANAGED FULL ACID TABLES
              LOG.info("Table is FULL ACID:");
              if ((processorCapabilities.contains(HIVEFULLACIDWRITE) && requiredCapabilities.contains(HIVEFULLACIDWRITE))
                  || (processorCapabilities.contains(CONNECTORWRITE) && requiredCapabilities.contains(CONNECTORWRITE))) {
                table.setAccessType(ACCESSTYPE_READWRITE); // clients have RW access to IUD ACID tables
              } else if ((processorCapabilities.contains(HIVEFULLACIDREAD) && requiredCapabilities.contains(HIVEFULLACIDREAD))
                   || (processorCapabilities.contains(CONNECTORREAD) && requiredCapabilities.contains(CONNECTORREAD))) {
                table.setAccessType(ACCESSTYPE_READONLY); // clients have RO access to IUD ACID tables
              } else {
                table.setAccessType(ACCESSTYPE_NONE); // clients have NO access to IUD ACID tables
              }
            }
          }
          LOG.info("setting required to " + requiredCapabilities + ",MANAGED:Access=" + table.getAccessType());
          ret.put(table, requiredCapabilities);
          break;
        case "VIRTUAL_VIEW":
          if (processorCapabilities.containsAll(requiredCapabilities)) {
            table.setAccessType(ACCESSTYPE_READONLY);
          } else {
            table.setAccessType(ACCESSTYPE_NONE);
          }
          ret.put(table, requiredCapabilities);
          break;
        case "MATERIALIZED_VIEW":
          if (processorCapabilities.containsAll(requiredCapabilities)) {
            table.setAccessType(ACCESSTYPE_READONLY);
          } else {
            table.setAccessType(ACCESSTYPE_NONE);
          }
          ret.put(table, requiredCapabilities);
          break;
        default:
          table.setAccessType(ACCESSTYPE_NONE);
          ret.put(table, requiredCapabilities);
          break;
      }
    }

    LOG.info("Transformer return list of " + ret.size());
    return ret;
  }

  @Override
  public List<Partition> transformPartitions(List<Partition> objects, List<String> processorCapabilities, String processorId) throws MetaException {
    LOG.info("Starting translation for partition for processor " + processorId + " on list " + objects.size());
    List<Partition> ret = new ArrayList<>();
    Map<String, Table> tableCache = new HashMap<>();
    int tableBuckets = 0;
    int partBuckets = 0;

    for (Partition partition : objects) {
      String tableName = partition.getTableName();
      String dbName = partition.getDbName();
      Table table = null;

      if (tableCache.containsKey(dbName + "." + tableName)) {
        table = tableCache.get(dbName + "." + tableName);
      } else {
        try {
          table = hmsHandler.get_table_core(MetaStoreUtils.getDefaultCatalog(null), dbName, tableName);
        } catch (Exception e) {
          throw new MetaException("Could not load table " + tableName + ":" + e.getMessage());
        }
        tableCache.put(dbName + "." + tableName, table);
      }
      Map<String, String> params = table.getParameters();
      String tableType = table.getTableType();
      String tCapabilities = params.get(OBJCAPABILITIES);
      tableBuckets = table.getSd().getNumBuckets();
      partBuckets = partition.getSd().getNumBuckets();
      LOG.info("Number of original part buckets=" + partBuckets);

      if (tCapabilities == null) {
        LOG.debug("Table " + table.getTableName() + " has no specific required capabilities");

        switch (tableType) {
          case "EXTERNAL_TABLE":
            if (partBuckets > 0 && !processorCapabilities.contains(HIVEBUCKET2)) {
              Partition newPartition = new Partition(partition);
              StorageDescriptor newSd = new StorageDescriptor(partition.getSd());
              newSd.setNumBuckets(-1); // remove bucketing info
              newPartition.setSd(newSd);
              ret.add(newPartition);
            } else {
              ret.add(partition);
            }
            break;
	  case "MANAGED_TABLE":
            String txnal = params.get("transactional");
            if (txnal == null || txnal.equalsIgnoreCase("FALSE")) { // non-ACID MANAGED table
              if (partBuckets > 0 && !processorCapabilities.contains(HIVEBUCKET2)) {
                Partition newPartition = new Partition(partition);
                StorageDescriptor newSd = new StorageDescriptor(partition.getSd());
                newSd.setNumBuckets(-1); // remove bucketing info
                newPartition.setSd(newSd);
                ret.add(newPartition);
                break;
              }
            }
            // INSERT or FULL ACID table, bucketing info to be retained
            ret.add(partition);
            break;
          default:
            ret.add(partition);
            break;
        }
      } else { // table has capabilities
        tCapabilities = tCapabilities.replaceAll("\\s","").toUpperCase(); // remove spaces between tCapabilities + toUppercase
        List<String> requiredCapabilities = Arrays.asList(tCapabilities.split(","));
        if (partBuckets <= 0 || processorCapabilities.containsAll(requiredCapabilities)) {
          ret.add(partition);
          continue;
        }

        switch (tableType) {
          case "EXTERNAL_TABLE":
            if (requiredCapabilities.contains(HIVEBUCKET2) && !processorCapabilities.contains(HIVEBUCKET2)) {
              Partition newPartition = new Partition(partition);
              StorageDescriptor newSd = new StorageDescriptor(partition.getSd());
              newSd.setNumBuckets(-1); // removing bucketing if HIVEBUCKET2 isnt specified
              newPartition.setSd(newSd);
              LOG.info("Removed bucketing information from partition");
              ret.add(newPartition);
              break;
            }
          case "MANAGED_TABLE":
            String txnal = params.get("transactional");
            if (txnal == null || txnal.equalsIgnoreCase("FALSE")) { // non-ACID MANAGED table
              if (!processorCapabilities.contains(HIVEBUCKET2)) {
                Partition newPartition = new Partition(partition);
                StorageDescriptor newSd = new StorageDescriptor(partition.getSd());
                newSd.setNumBuckets(-1); // remove bucketing info
                newPartition.setSd(newSd);
                ret.add(newPartition);
                break;
              }
            }
            ret.add(partition);
            break;
          default:
            ret.add(partition);
            break;
        }
      }
    }
    LOG.info("Returning partition set of size " + ret.size());
    return ret;
  }
}

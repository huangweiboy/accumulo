/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.util;

import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.clientImpl.ScannerImpl;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.schema.Ample.TabletMutator;
import org.apache.accumulo.core.metadata.schema.DataFileValue;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataTime;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.LocationType;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.fate.zookeeper.ZooLock;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.fs.FileRef;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.hadoop.io.Text;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MasterMetadataUtil {

  private static final Logger log = LoggerFactory.getLogger(MasterMetadataUtil.class);

  public static void addNewTablet(ServerContext context, KeyExtent extent, String path,
      TServerInstance location, Map<FileRef,DataFileValue> datafileSizes,
      Map<Long,? extends Collection<FileRef>> bulkLoadedFiles, MetadataTime time, long lastFlushID,
      long lastCompactID, ZooLock zooLock) {

    TabletMutator tablet = context.getAmple().mutateTablet(extent);
    tablet.putPrevEndRow(extent.getPrevEndRow());
    tablet.putZooLock(zooLock);
    tablet.putDir(path);
    tablet.putTime(time);

    if (lastFlushID > 0)
      tablet.putFlushId(lastFlushID);

    if (lastCompactID > 0)
      tablet.putCompactionId(lastCompactID);

    if (location != null) {
      tablet.putLocation(location, LocationType.CURRENT);
      tablet.deleteLocation(location, LocationType.FUTURE);
    }

    datafileSizes.forEach(tablet::putFile);

    for (Entry<Long,? extends Collection<FileRef>> entry : bulkLoadedFiles.entrySet()) {
      for (FileRef ref : entry.getValue()) {
        tablet.putBulkFile(ref, entry.getKey().longValue());
      }
    }

    tablet.mutate();
  }

  public static KeyExtent fixSplit(ServerContext context, TabletMetadata meta, ZooLock lock)
      throws AccumuloException {
    log.info("Incomplete split {} attempting to fix", meta.getExtent());

    if (meta.getSplitRatio() == null) {
      throw new IllegalArgumentException(
          "Metadata entry does not have split ratio (" + meta.getExtent() + ")");
    }

    if (meta.getTime() == null) {
      throw new IllegalArgumentException(
          "Metadata entry does not have time (" + meta.getExtent() + ")");
    }

    return fixSplit(context, meta.getTableId(), meta.getExtent().getMetadataEntry(),
        meta.getPrevEndRow(), meta.getOldPrevEndRow(), meta.getSplitRatio(), lock);
  }

  private static KeyExtent fixSplit(ServerContext context, TableId tableId, Text metadataEntry,
      Text metadataPrevEndRow, Text oper, double splitRatio, ZooLock lock)
      throws AccumuloException {
    if (metadataPrevEndRow == null)
      // something is wrong, this should not happen... if a tablet is split, it will always have a
      // prev end row....
      throw new AccumuloException(
          "Split tablet does not have prev end row, something is amiss, extent = " + metadataEntry);

    // check to see if prev tablet exist in metadata tablet
    Key prevRowKey = new Key(new Text(TabletsSection.getRow(tableId, metadataPrevEndRow)));

    try (ScannerImpl scanner2 = new ScannerImpl(context, MetadataTable.ID, Authorizations.EMPTY)) {
      scanner2.setRange(new Range(prevRowKey, prevRowKey.followingKey(PartialKey.ROW)));

      if (!scanner2.iterator().hasNext()) {
        log.info("Rolling back incomplete split {} {}", metadataEntry, metadataPrevEndRow);
        MetadataTableUtil.rollBackSplit(metadataEntry, oper, context, lock);
        return new KeyExtent(metadataEntry, oper);
      } else {
        log.info("Finishing incomplete split {} {}", metadataEntry, metadataPrevEndRow);

        List<FileRef> highDatafilesToRemove = new ArrayList<>();

        SortedMap<FileRef,DataFileValue> origDatafileSizes = new TreeMap<>();
        SortedMap<FileRef,DataFileValue> highDatafileSizes = new TreeMap<>();
        SortedMap<FileRef,DataFileValue> lowDatafileSizes = new TreeMap<>();

        try (Scanner scanner3 = new ScannerImpl(context, MetadataTable.ID, Authorizations.EMPTY)) {
          Key rowKey = new Key(metadataEntry);

          scanner3.fetchColumnFamily(DataFileColumnFamily.NAME);
          scanner3.setRange(new Range(rowKey, rowKey.followingKey(PartialKey.ROW)));

          for (Entry<Key,Value> entry : scanner3) {
            if (entry.getKey().compareColumnFamily(DataFileColumnFamily.NAME) == 0) {
              origDatafileSizes.put(new FileRef(context.getVolumeManager(), entry.getKey()),
                  new DataFileValue(entry.getValue().get()));
            }
          }
        }

        MetadataTableUtil.splitDatafiles(metadataPrevEndRow, splitRatio, new HashMap<>(),
            origDatafileSizes, lowDatafileSizes, highDatafileSizes, highDatafilesToRemove);

        MetadataTableUtil.finishSplit(metadataEntry, highDatafileSizes, highDatafilesToRemove,
            context, lock);

        return new KeyExtent(metadataEntry, KeyExtent.encodePrevEndRow(metadataPrevEndRow));
      }
    }
  }

  private static TServerInstance getTServerInstance(String address, ZooLock zooLock) {
    while (true) {
      try {
        return new TServerInstance(address, zooLock.getSessionId());
      } catch (KeeperException | InterruptedException e) {
        log.error("{}", e.getMessage(), e);
      }
      sleepUninterruptibly(1, TimeUnit.SECONDS);
    }
  }

  public static void replaceDatafiles(ServerContext context, KeyExtent extent,
      Set<FileRef> datafilesToDelete, Set<FileRef> scanFiles, FileRef path, Long compactionId,
      DataFileValue size, String address, TServerInstance lastLocation, ZooLock zooLock) {
    replaceDatafiles(context, extent, datafilesToDelete, scanFiles, path, compactionId, size,
        address, lastLocation, zooLock, true);
  }

  public static void replaceDatafiles(ServerContext context, KeyExtent extent,
      Set<FileRef> datafilesToDelete, Set<FileRef> scanFiles, FileRef path, Long compactionId,
      DataFileValue size, String address, TServerInstance lastLocation, ZooLock zooLock,
      boolean insertDeleteFlags) {

    context.getAmple().putGcCandidates(extent.getTableId(), datafilesToDelete);

    TabletMutator tablet = context.getAmple().mutateTablet(extent);

    datafilesToDelete.forEach(tablet::deleteFile);
    scanFiles.forEach(tablet::putScan);

    if (size.getNumEntries() > 0)
      tablet.putFile(path, size);

    if (compactionId != null)
      tablet.putCompactionId(compactionId);

    TServerInstance self = getTServerInstance(address, zooLock);
    tablet.putLocation(self, LocationType.LAST);

    // remove the old location
    if (lastLocation != null && !lastLocation.equals(self))
      tablet.deleteLocation(lastLocation, LocationType.LAST);

    tablet.putZooLock(zooLock);

    tablet.mutate();
  }

  /**
   * new data file update function adds one data file to a tablet's list
   *
   * @param path
   *          should be relative to the table directory
   *
   */
  public static void updateTabletDataFile(ServerContext context, KeyExtent extent, FileRef path,
      FileRef mergeFile, DataFileValue dfv, MetadataTime time, Set<FileRef> filesInUseByScans,
      String address, ZooLock zooLock, Set<String> unusedWalLogs, TServerInstance lastLocation,
      long flushId) {
    if (extent.isRootTablet()) {
      updateRootTabletDataFile(context, unusedWalLogs);
    } else {
      updateForTabletDataFile(context, extent, path, mergeFile, dfv, time, filesInUseByScans,
          address, zooLock, unusedWalLogs, lastLocation, flushId);
    }

  }

  /**
   * Update the data file for the root tablet
   */
  private static void updateRootTabletDataFile(ServerContext context, Set<String> unusedWalLogs) {
    if (unusedWalLogs != null) {
      TabletMutator tablet = context.getAmple().mutateTablet(RootTable.EXTENT);
      unusedWalLogs.forEach(tablet::deleteWal);
      tablet.mutate();
    }
  }

  /**
   * Create an update that updates a tablet
   *
   */
  private static void updateForTabletDataFile(ServerContext context, KeyExtent extent, FileRef path,
      FileRef mergeFile, DataFileValue dfv, MetadataTime time, Set<FileRef> filesInUseByScans,
      String address, ZooLock zooLock, Set<String> unusedWalLogs, TServerInstance lastLocation,
      long flushId) {

    TabletMutator tablet = context.getAmple().mutateTablet(extent);

    if (dfv.getNumEntries() > 0) {
      tablet.putFile(path, dfv);
      tablet.putTime(time);

      TServerInstance self = getTServerInstance(address, zooLock);
      tablet.putLocation(self, LocationType.LAST);

      // remove the old location
      if (lastLocation != null && !lastLocation.equals(self)) {
        tablet.deleteLocation(lastLocation, LocationType.LAST);
      }
    }
    tablet.putFlushId(flushId);

    if (mergeFile != null) {
      tablet.deleteFile(mergeFile);
    }

    unusedWalLogs.forEach(tablet::deleteWal);
    filesInUseByScans.forEach(tablet::putScan);

    tablet.putZooLock(zooLock);

    tablet.mutate();
  }
}

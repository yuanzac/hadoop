/**
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
package org.apache.hadoop.hdfs;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.RollingUpgradeInfo;
import org.apache.hadoop.hdfs.qjournal.MiniQJMHACluster;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.namenode.NameNodeLayoutVersion;
import org.junit.Assert;
import org.junit.Test;

public class TestRollingUpgradeDowngrade {

  @Test(timeout = 300000)
  public void testDowngrade() throws IOException {
    final Configuration conf = new HdfsConfiguration();
    MiniQJMHACluster cluster = null;
    final Path foo = new Path("/foo");
    final Path bar = new Path("/bar");

    try {
      cluster = new MiniQJMHACluster.Builder(conf).build();
      MiniDFSCluster dfsCluster = cluster.getDfsCluster();
      dfsCluster.waitActive();

      dfsCluster.transitionToActive(0);
      DistributedFileSystem dfs = dfsCluster.getFileSystem(0);
      dfs.mkdirs(foo);

      // start rolling upgrade
      RollingUpgradeInfo info = dfs
          .rollingUpgrade(RollingUpgradeAction.PREPARE);
      Assert.assertTrue(info.isStarted());
      dfs.mkdirs(bar);
      dfs.close();

      dfsCluster.restartNameNode(0, true, "-rollingUpgrade", "downgrade");
      // shutdown NN1
      dfsCluster.shutdownNameNode(1);
      dfsCluster.transitionToActive(0);

      dfs = dfsCluster.getFileSystem(0);
      Assert.assertTrue(dfs.exists(foo));
      Assert.assertTrue(dfs.exists(bar));
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Ensure that during downgrade the NN fails to load a fsimage with newer
   * format.
   */
  @Test(expected = IncorrectVersionException.class)
  public void testRejectNewFsImage() throws IOException {
    final Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      NNStorage storage = spy(cluster.getNameNode().getFSImage().getStorage());
      int futureVersion = NameNodeLayoutVersion.CURRENT_LAYOUT_VERSION - 1;
      doReturn(futureVersion).when(storage).getServiceLayoutVersion();
      storage.writeAll();
      cluster.restartNameNode(0, true, "-rollingUpgrade", "downgrade");
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}

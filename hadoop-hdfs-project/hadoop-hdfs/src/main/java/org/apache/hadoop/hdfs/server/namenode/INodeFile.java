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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockCollection;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;

/** I-node for closed file. */
@InterfaceAudience.Private
class INodeFile extends INode implements BlockCollection {
  /** Cast INode to INodeFile. */
  public static INodeFile valueOf(INode inode, String path) throws IOException {
    if (inode == null) {
      throw new FileNotFoundException("File does not exist: " + path);
    }
    if (!(inode instanceof INodeFile)) {
      throw new FileNotFoundException("Path is not a file: " + path);
    }
    return (INodeFile)inode;
  }

  static final FsPermission UMASK = FsPermission.createImmutable((short)0111);

  //Number of bits for Block size
  static final short BLOCKBITS = 48;

  //Header mask 64-bit representation
  //Format: [16 bits for replication][48 bits for PreferredBlockSize]
  static final long HEADERMASK = 0xffffL << BLOCKBITS;

  private long header;

  private BlockInfo[] blocks;

  INodeFile(PermissionStatus permissions, BlockInfo[] blklist,
                      short replication, long modificationTime,
                      long atime, long preferredBlockSize) {
    super(permissions, modificationTime, atime);
    this.setReplication(replication);
    this.setPreferredBlockSize(preferredBlockSize);
    this.blocks = blklist;
  }

  /**
   * Set the {@link FsPermission} of this {@link INodeFile}.
   * Since this is a file,
   * the {@link FsAction#EXECUTE} action, if any, is ignored.
   */
  @Override
  void setPermission(FsPermission permission) {
    super.setPermission(permission.applyUMask(UMASK));
  }

  /** @return the replication factor of the file. */
  @Override
  public short getBlockReplication() {
    return (short) ((header & HEADERMASK) >> BLOCKBITS);
  }

  void setReplication(short replication) {
    if(replication <= 0)
       throw new IllegalArgumentException("Unexpected value for the replication");
    header = ((long)replication << BLOCKBITS) | (header & ~HEADERMASK);
  }

  /** @return preferred block size (in bytes) of the file. */
  @Override
  public long getPreferredBlockSize() {
    return header & ~HEADERMASK;
  }

  private void setPreferredBlockSize(long preferredBlkSize) {
    if((preferredBlkSize < 0) || (preferredBlkSize > ~HEADERMASK ))
       throw new IllegalArgumentException("Unexpected value for the block size");
    header = (header & HEADERMASK) | (preferredBlkSize & ~HEADERMASK);
  }

  /** @return the blocks of the file. */
  @Override
  public BlockInfo[] getBlocks() {
    return this.blocks;
  }

  /**
   * append array of blocks to this.blocks
   */
  void appendBlocks(INodeFile [] inodes, int totalAddedBlocks) {
    int size = this.blocks.length;
    
    BlockInfo[] newlist = new BlockInfo[size + totalAddedBlocks];
    System.arraycopy(this.blocks, 0, newlist, 0, size);
    
    for(INodeFile in: inodes) {
      System.arraycopy(in.blocks, 0, newlist, size, in.blocks.length);
      size += in.blocks.length;
    }
    
    for(BlockInfo bi: newlist) {
      bi.setBlockCollection(this);
    }
    setBlocks(newlist);
  }
  
  /**
   * add a block to the block list
   */
  void addBlock(BlockInfo newblock) {
    if (this.blocks == null) {
      this.setBlocks(new BlockInfo[]{newblock});
    } else {
      int size = this.blocks.length;
      BlockInfo[] newlist = new BlockInfo[size + 1];
      System.arraycopy(this.blocks, 0, newlist, 0, size);
      newlist[size] = newblock;
      this.setBlocks(newlist);
    }
  }

  /** Set the block of the file at the given index. */
  public void setBlock(int idx, BlockInfo blk) {
    this.blocks[idx] = blk;
  }

  /** Set the blocks. */
  public void setBlocks(BlockInfo[] blocks) {
    this.blocks = blocks;
  }

  @Override
  int collectSubtreeBlocksAndClear(List<Block> v) {
    parent = null;
    if(blocks != null && v != null) {
      for (BlockInfo blk : blocks) {
        v.add(blk);
        blk.setBlockCollection(null);
      }
    }
    setBlocks(null);
    return 1;
  }
  
  @Override
  public String getName() {
    // Get the full path name of this inode.
    return getFullPathName();
  }


  @Override
  long[] computeContentSummary(long[] summary) {
    summary[0] += computeFileSize(true);
    summary[1]++;
    summary[3] += diskspaceConsumed();
    return summary;
  }

  /** Compute file size.
   * May or may not include BlockInfoUnderConstruction.
   */
  long computeFileSize(boolean includesBlockInfoUnderConstruction) {
    if (blocks == null || blocks.length == 0) {
      return 0;
    }
    final int last = blocks.length - 1;
    //check if the last block is BlockInfoUnderConstruction
    long bytes = blocks[last] instanceof BlockInfoUnderConstruction
                 && !includesBlockInfoUnderConstruction?
                     0: blocks[last].getNumBytes();
    for(int i = 0; i < last; i++) {
      bytes += blocks[i].getNumBytes();
    }
    return bytes;
  }
  

  @Override
  DirCounts spaceConsumedInTree(DirCounts counts) {
    counts.nsCount += 1;
    counts.dsCount += diskspaceConsumed();
    return counts;
  }

  long diskspaceConsumed() {
    return diskspaceConsumed(blocks);
  }
  
  private long diskspaceConsumed(Block[] blkArr) {
    long size = 0;
    if(blkArr == null) 
      return 0;
    
    for (Block blk : blkArr) {
      if (blk != null) {
        size += blk.getNumBytes();
      }
    }
    /* If the last block is being written to, use prefferedBlockSize
     * rather than the actual block size.
     */
    if (blkArr.length > 0 && blkArr[blkArr.length-1] != null && 
        isUnderConstruction()) {
      size += getPreferredBlockSize() - blkArr[blkArr.length-1].getNumBytes();
    }
    return size * getBlockReplication();
  }
  
  /**
   * Return the penultimate allocated block for this file.
   */
  BlockInfo getPenultimateBlock() {
    if (blocks == null || blocks.length <= 1) {
      return null;
    }
    return blocks[blocks.length - 2];
  }

  @Override
  public BlockInfo getLastBlock() throws IOException {
    return blocks == null || blocks.length == 0? null: blocks[blocks.length-1];
  }

  @Override
  public int numBlocks() {
    return blocks == null ? 0 : blocks.length;
  }
}

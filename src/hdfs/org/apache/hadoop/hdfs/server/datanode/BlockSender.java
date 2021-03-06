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
package org.apache.hadoop.hdfs.server.datanode;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.SocketOutputStream;
import org.apache.hadoop.util.ChecksumUtil;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;

/**
 * Reads a block from the disk and sends it to a recipient.
 */
public class BlockSender implements java.io.Closeable, FSConstants {
  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  
  private int namespaceId;
  protected Block block; // the block to read from
  protected InputStream blockIn; // data stream
  protected long blockInPosition = -1; // updated while using transferTo().
  protected DataInputStream checksumIn; // checksum datastream
  protected DataChecksum checksum; // checksum stream
  protected long offset; // starting position to read
  protected long endOffset; // ending position
  protected long blockLength;
  protected int bytesPerChecksum; // chunk size
  protected int checksumSize; // checksum size
  protected boolean corruptChecksumOk; // if need to verify checksum
  protected boolean chunkOffsetOK; // if need to send chunk offset
  protected long seqno; // sequence number of packet
  private boolean ignoreChecksum = false;

  protected boolean transferToAllowed = true;
  protected boolean blockReadFully; //set when the whole block is read
  protected boolean verifyChecksum; //if true, check is verified while reading
  protected DataTransferThrottler throttler;
  protected String clientTraceFmt; // format of client trace log message
  protected MemoizedBlock memoizedBlock;

  /**
   * Minimum buffer used while sending data to clients. Used only if
   * transferTo() is enabled. 64KB is not that large. It could be larger, but
   * not sure if there will be much more improvement.
   */
  protected static final int MIN_BUFFER_WITH_TRANSFERTO = 64*1024;

  
  BlockSender(int namespaceId, Block block, long startOffset, long length,
              boolean corruptChecksumOk, boolean chunkOffsetOK,
              boolean verifyChecksum, DataNode datanode) throws IOException {
    this(namespaceId, block, startOffset, length, false, corruptChecksumOk,
        chunkOffsetOK, verifyChecksum, datanode, null);
  }

  BlockSender(int namespaceId, Block block, long startOffset, long length,
              boolean ignoreChecksum, boolean corruptChecksumOk, boolean chunkOffsetOK,
              boolean verifyChecksum, DataNode datanode, String clientTraceFmt)
      throws IOException {
    
    long blockLength = datanode.data.getVisibleLength(namespaceId, block);
    boolean transferToAllowed = datanode.transferToAllowed;
    this.ignoreChecksum = ignoreChecksum;
    
    InputStreamFactory streamFactory = new BlockInputStreamFactory(namespaceId, block, datanode.data);
    DataInputStream metadataIn = null;
    if (!ignoreChecksum) {
      try {
        metadataIn = new DataInputStream(new BufferedInputStream(
            datanode.data.getMetaDataInputStream(namespaceId, block),
            BUFFER_SIZE));
      } catch (IOException e) {
        if (!corruptChecksumOk
            || datanode.data.metaFileExists(namespaceId, block)) {
          throw e;
        }
        metadataIn = null;
      }
    }
    initialize(namespaceId, block, blockLength, startOffset, length,
        corruptChecksumOk, chunkOffsetOK, verifyChecksum, transferToAllowed,
        metadataIn, streamFactory, clientTraceFmt);
  }
  
  public BlockSender(int namespaceId, Block block, long blockLength, long startOffset, long length,
              boolean corruptChecksumOk, boolean chunkOffsetOK,
              boolean verifyChecksum, boolean transferToAllowed,
              DataInputStream metadataIn, InputStreamFactory streamFactory
              ) throws IOException {
    initialize(namespaceId, block, blockLength, startOffset, length,
         corruptChecksumOk, chunkOffsetOK, verifyChecksum, transferToAllowed,
         metadataIn, streamFactory, null);
  }

  private void initialize(int namespaceId, Block block, long blockLength,
      long startOffset, long length, boolean corruptChecksumOk,
      boolean chunkOffsetOK, boolean verifyChecksum, boolean transferToAllowed,
      DataInputStream metadataIn, InputStreamFactory streamFactory,
      String clientTraceFmt) throws IOException {
    try {
      this.namespaceId = namespaceId;
      this.block = block;
      this.chunkOffsetOK = chunkOffsetOK;
      this.corruptChecksumOk = corruptChecksumOk;
      this.verifyChecksum = verifyChecksum;
      this.blockLength = blockLength;
      this.transferToAllowed = transferToAllowed;
      this.clientTraceFmt = clientTraceFmt;

      if ( !corruptChecksumOk || metadataIn != null) {
        this.checksumIn = metadataIn;

        // read and handle the common header here. For now just a version
       BlockMetadataHeader header = BlockMetadataHeader.readHeader(checksumIn);
       short version = header.getVersion();

        if (version != FSDataset.METADATA_VERSION) {
          LOG.warn("Wrong version (" + version + ") for metadata file for "
              + block + " ignoring ...");
        }
        checksum = header.getChecksum();
      } else {
        if (!ignoreChecksum) {
          LOG.warn("Could not find metadata file for " + block);
        }
        // This only decides the buffer size. Use BUFFER_SIZE?
        checksum = DataChecksum.newDataChecksum(DataChecksum.CHECKSUM_NULL,
            16 * 1024);
      }

      /* If bytesPerChecksum is very large, then the metadata file
       * is mostly corrupted. For now just truncate bytesPerchecksum to
       * blockLength.
       */        
      bytesPerChecksum = checksum.getBytesPerChecksum();
      if (bytesPerChecksum > 10*1024*1024 && bytesPerChecksum > blockLength){
        checksum = DataChecksum.newDataChecksum(checksum.getChecksumType(),
                                   Math.max((int)blockLength, 10*1024*1024));
        bytesPerChecksum = checksum.getBytesPerChecksum();        
      }
      checksumSize = checksum.getChecksumSize();

      if (length < 0) {
        length = blockLength;
      }

      endOffset = blockLength;
      if (startOffset < 0 || startOffset > endOffset
          || (length + startOffset) > endOffset) {
        String msg = " Offset " + startOffset + " and length " + length
        + " don't match block " + block + " ( blockLen " + endOffset + " )";
        LOG.warn("sendBlock() : " + msg);
        throw new IOException(msg);
      }

      
      offset = (startOffset - (startOffset % bytesPerChecksum));
      if (length >= 0) {
        // Make sure endOffset points to end of a checksumed chunk.
        long tmpLen = startOffset + length;
        if (tmpLen % bytesPerChecksum != 0) {
          tmpLen += (bytesPerChecksum - tmpLen % bytesPerChecksum);
        }
        if (tmpLen < endOffset) {
          endOffset = tmpLen;
        }
      }

      // seek to the right offsets
      if (offset > 0) {
        long checksumSkip = (offset / bytesPerChecksum) * checksumSize;
        // note blockInStream is  seeked when created below
        if (checksumSkip > 0) {
          // Should we use seek() for checksum file as well?
          IOUtils.skipFully(checksumIn, checksumSkip);
        }
      }
      seqno = 0;
      
      blockIn = streamFactory.createStream(offset);
      memoizedBlock = new MemoizedBlock(blockIn, blockLength, streamFactory, block);
    } catch (IOException ioe) {
      IOUtils.closeStream(this);
      IOUtils.closeStream(blockIn);
      throw ioe;
    }
  }

  /**
   * close opened files.
   */
  public void close() throws IOException {
    IOException ioe = null;
    // close checksum file
    if(checksumIn!=null) {
      try {
        checksumIn.close();
      } catch (IOException e) {
        ioe = e;
      }
      checksumIn = null;
    }
    // close data file
    if(blockIn!=null) {
      try {
        blockIn.close();
      } catch (IOException e) {
        ioe = e;
      }
      blockIn = null;
    }
    // throw IOException if there is any
    if(ioe!= null) {
      throw ioe;
    }
  }

  /**
   * Converts an IOExcpetion (not subclasses) to SocketException.
   * This is typically done to indicate to upper layers that the error 
   * was a socket error rather than often more serious exceptions like 
   * disk errors.
   */
  protected static IOException ioeToSocketException(IOException ioe) {
    if (ioe.getClass().equals(IOException.class)) {
      // "se" could be a new class in stead of SocketException.
      IOException se = new SocketException("Original Exception : " + ioe);
      se.initCause(ioe);
      /* Change the stacktrace so that original trace is not truncated
       * when printed.*/ 
      se.setStackTrace(ioe.getStackTrace());
      return se;
    }
    // otherwise just return the same exception.
    return ioe;
  }

  /**
   * Sends upto maxChunks chunks of data.
   * 
   * When blockInPosition is >= 0, assumes 'out' is a 
   * {@link SocketOutputStream} and tries 
   * {@link SocketOutputStream#transferToFully(FileChannel, long, int)} to
   * send data (and updates blockInPosition).
   */
  private int sendChunks(ByteBuffer pkt, int maxChunks, OutputStream out) 
                         throws IOException {
    // Sends multiple chunks in one packet with a single write().
    int len = (int) Math.min(endOffset - offset,
                            (((long) bytesPerChecksum) * ((long) maxChunks)));

    // truncate len so that any partial chunks will be sent as a final packet.
    // this is not necessary for correctness, but partial chunks are 
    // ones that may be recomputed and sent via buffer copy, so try to minimize
    // those bytes
    if (len > bytesPerChecksum && len % bytesPerChecksum != 0) {
      len -= len % bytesPerChecksum;
    }

    if (len == 0) {
      return 0;
    }

    int numChunks = (len + bytesPerChecksum - 1)/bytesPerChecksum;
    int packetLen = len + numChunks*checksumSize + 4;
    pkt.clear();

    // write packet header
    pkt.putInt(packetLen);
    pkt.putLong(offset);
    pkt.putLong(seqno);
    pkt.put((byte)((offset + len >= endOffset) ? 1 : 0));
               //why no ByteBuf.putBoolean()?
    pkt.putInt(len);
    
    int checksumOff = pkt.position();
    int checksumLen = numChunks * checksumSize;
    byte[] buf = pkt.array();
    
    if (checksumSize > 0 && checksumIn != null) {
      try {
        checksumIn.readFully(buf, checksumOff, checksumLen);
      } catch (IOException e) {
        LOG.warn(" Could not read or failed to veirfy checksum for data" +
                 " at offset " + offset + " for block " + block + " got : "
                 + StringUtils.stringifyException(e));
        IOUtils.closeStream(checksumIn);
        checksumIn = null;
        if (corruptChecksumOk) {
          if (checksumOff < checksumLen) {
            // Just fill the array with zeros.
            Arrays.fill(buf, checksumOff, checksumLen, (byte) 0);
          }
        } else {
          throw e;
        }
      }
    }
    
    int dataOff = checksumOff + checksumLen;
    
    if (blockInPosition < 0) {
      //normal transfer
      IOUtils.readFully(blockIn, buf, dataOff, len);

      if (verifyChecksum) {
        int dOff = dataOff;
        int cOff = checksumOff;
        int dLeft = len;

        for (int i=0; i<numChunks; i++) {
          checksum.reset();
          int dLen = Math.min(dLeft, bytesPerChecksum);
          checksum.update(buf, dOff, dLen);
          if (!checksum.compare(buf, cOff)) {
            throw new ChecksumException("Checksum failed at " + 
                                        (offset + len - dLeft), len);
          }
          dLeft -= dLen;
          dOff += dLen;
          cOff += checksumSize;
        }
      }
      
      // only recompute checksum if we can't trust the meta data due to 
      // concurrent writes
      if (memoizedBlock.hasBlockChanged(len)) {
        ChecksumUtil.updateChunkChecksum(
          buf, checksumOff, dataOff, len, checksum
        );
      }
      
      try {
        out.write(buf, 0, dataOff + len);
      } catch (IOException e) {
        throw ioeToSocketException(e);
      }
    } else {
      try {
        //use transferTo(). Checks on out and blockIn are already done. 
        SocketOutputStream sockOut = (SocketOutputStream) out;
        FileChannel fileChannel = ((FileInputStream) blockIn).getChannel();

        if (memoizedBlock.hasBlockChanged(len)) {
          fileChannel.position(blockInPosition);
          IOUtils.readFileChannelFully(fileChannel, buf, dataOff, len);
          
          ChecksumUtil.updateChunkChecksum(
            buf, checksumOff, dataOff, len, checksum);          
          sockOut.write(buf, 0, dataOff + len);
        } else {
          //first write the packet
          sockOut.write(buf, 0, dataOff);
          // no need to flush. since we know out is not a buffered stream.
          sockOut.transferToFully(fileChannel, blockInPosition, len);
        }

        blockInPosition += len;

      } catch (IOException e) {
      /* exception while writing to the client (well, with transferTo(),
       * it could also be while reading from the local file).
       */
        throw ioeToSocketException(e);
      }
    }

    if (throttler != null) { // rebalancing so throttle
      throttler.throttle(packetLen);
    }

    return len;
  }

  /**
   * sendBlock() is used to read block and its metadata and stream the data to
   * either a client or to another datanode. 
   * 
   * @param out  stream to which the block is written to
   * @param baseStream optional. if non-null, <code>out</code> is assumed to 
   *        be a wrapper over this stream. This enables optimizations for
   *        sending the data, e.g. 
   *        {@link SocketOutputStream#transferToFully(FileChannel, 
   *        long, int)}.
   * @param throttler for sending data.
   * @return total bytes reads, including crc.
   */
  public long sendBlock(DataOutputStream out, OutputStream baseStream, 
                 DataTransferThrottler throttler) throws IOException {
    return sendBlock(out, baseStream, throttler, null);
  }

  /**
   * sendBlock() is used to read block and its metadata and stream the data to
   * either a client or to another datanode. 
   * 
   * @param out  stream to which the block is written to
   * @param baseStream optional. if non-null, <code>out</code> is assumed to 
   *        be a wrapper over this stream. This enables optimizations for
   *        sending the data, e.g. 
   *        {@link SocketOutputStream#transferToFully(FileChannel, 
   *        long, int)}.
   * @param throttler for sending data.
   * @param progress for signalling progress.
   * @return total bytes reads, including crc.
   */
  public long sendBlock(DataOutputStream out, OutputStream baseStream, 
       DataTransferThrottler throttler, Progressable progress) throws IOException {
    if( out == null ) {
      throw new IOException( "out stream is null" );
    }
    this.throttler = throttler;

    long initialOffset = offset;
    long totalRead = 0;
    OutputStream streamForSendChunks = out;
    
    final long startTime = ClientTraceLog.isInfoEnabled() ? System.nanoTime() : 0; 
    try {
      try {
        checksum.writeHeader(out);
        if ( chunkOffsetOK ) {
          out.writeLong( offset );
        }
        out.flush();
      } catch (IOException e) { //socket error
        throw ioeToSocketException(e);
      }
      
      int maxChunksPerPacket;
      int pktSize = SIZE_OF_INTEGER + DataNode.PKT_HEADER_LEN;
      
      if (transferToAllowed && !verifyChecksum && 
          baseStream instanceof SocketOutputStream && 
          blockIn instanceof FileInputStream) {
        
        FileChannel fileChannel = ((FileInputStream)blockIn).getChannel();
        
        // blockInPosition also indicates sendChunks() uses transferTo.
        blockInPosition = fileChannel.position();
        streamForSendChunks = baseStream;
        
        // assure a mininum buffer size.
        maxChunksPerPacket = (Math.max(BUFFER_SIZE,
                                       MIN_BUFFER_WITH_TRANSFERTO)
                              + bytesPerChecksum - 1)/bytesPerChecksum;
        
        // packet buffer has to be able to do a normal transfer in the case
        // of recomputing checksum
        pktSize += (bytesPerChecksum + checksumSize) * maxChunksPerPacket;
      } else {
        maxChunksPerPacket = Math.max(1,
                 (BUFFER_SIZE + bytesPerChecksum - 1)/bytesPerChecksum);
        pktSize += (bytesPerChecksum + checksumSize) * maxChunksPerPacket;
      }

      ByteBuffer pktBuf = ByteBuffer.allocate(pktSize);

      while (endOffset > offset) {
        long len = sendChunks(pktBuf, maxChunksPerPacket, 
                              streamForSendChunks);
        if (progress != null) {
          progress.progress();
        }
        offset += len;
        totalRead += len + ((len + bytesPerChecksum - 1)/bytesPerChecksum*
                            checksumSize);
        seqno++;
      }
      try {
        out.writeInt(0); // mark the end of block        
        out.flush();
      } catch (IOException e) { //socket error
        throw ioeToSocketException(e);
      }
    }
    catch (RuntimeException e) {
      LOG.error("unexpected exception sending block", e);
      
      throw new IOException("unexpected runtime exception", e);
    } 
    finally {
      if (clientTraceFmt != null) {
        final long endTime = System.nanoTime();
        ClientTraceLog.info(String.format(clientTraceFmt, totalRead, initialOffset, endTime - startTime));
      }
      close();
    }

    blockReadFully = (initialOffset == 0 && offset >= blockLength);

    return totalRead;
  }
  
  boolean isBlockReadFully() {
    return blockReadFully;
  }
  
  public static interface InputStreamFactory {
    public InputStream createStream(long offset) throws IOException; 
  }
  
  private static class BlockInputStreamFactory implements InputStreamFactory {
    private final int namespaceId;
    private final Block block;
    private final FSDatasetInterface data;

    private BlockInputStreamFactory(int namespaceId, Block block, FSDatasetInterface data) {
      this.namespaceId = namespaceId;
      this.block = block;
      this.data = data;
    }

    @Override
    public InputStream createStream(long offset) throws IOException {
      return data.getBlockInputStream(namespaceId, block, offset);
    }
    
    public FSDatasetInterface getDataset() {
      return this.data;
    }
  }

  /**
   * helper class used to track if a block's meta data is verifiable or not
   */
  class MemoizedBlock {
    // block data stream 
    private InputStream inputStream;
    //  visible block length
    private long blockLength;
    private final Block block;
    private final InputStreamFactory isf;

    private MemoizedBlock(
      InputStream inputStream,
      long blockLength,
      InputStreamFactory isf,
      Block block) {
      this.inputStream = inputStream;
      this.blockLength = blockLength;
      this.isf = isf;
      this.block = block;
    }

    // logic: if we are starting or ending on a partial chunk and the block
    // has more data than we were told at construction, the block has 'changed'
    // in a way that we care about (ie, we can't trust crc data) 
    boolean hasBlockChanged(long dataLen) throws IOException {
      // check if we are using transferTo since we tell if the file has changed
      // (blockInPosition >= 0 => we are using transferTo and File Channels
      if (BlockSender.this.blockInPosition >= 0) {
        long currentLength = ((FileInputStream) inputStream).getChannel().size();
        
        return (blockInPosition % bytesPerChecksum != 0 || 
            dataLen % bytesPerChecksum != 0) &&
          currentLength > blockLength;

      } else {
        FSDatasetInterface ds = null;
        if (isf instanceof BlockInputStreamFactory) {
          ds = ((BlockInputStreamFactory)isf).getDataset();
        }
          
        // offset is the offset into the block
        return (BlockSender.this.offset % bytesPerChecksum != 0 || 
            dataLen % bytesPerChecksum != 0) &&
            ds != null && ds.getFinalizedBlockLength(namespaceId, block) > blockLength;
      }
    }
  }
}

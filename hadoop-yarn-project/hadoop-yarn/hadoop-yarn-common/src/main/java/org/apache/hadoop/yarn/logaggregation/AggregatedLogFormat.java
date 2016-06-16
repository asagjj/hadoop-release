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

package org.apache.hadoop.yarn.logaggregation;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.SecureIOUtils;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.file.tfile.TFile;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.LogAggregationContext;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Times;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

@Public
@Evolving
public class AggregatedLogFormat {

  private static final Log LOG = LogFactory.getLog(AggregatedLogFormat.class);
  private static final LogKey APPLICATION_ACL_KEY = new LogKey("APPLICATION_ACL");
  private static final LogKey APPLICATION_OWNER_KEY = new LogKey("APPLICATION_OWNER");
  private static final LogKey VERSION_KEY = new LogKey("VERSION");
  private static final Map<String, LogKey> RESERVED_KEYS;
  //Maybe write out the retention policy.
  //Maybe write out a list of containerLogs skipped by the retention policy.
  private static final int VERSION = 1;

  /**
   * Umask for the log file.
   */
  private static final FsPermission APP_LOG_FILE_UMASK = FsPermission
      .createImmutable((short) (0640 ^ 0777));


  static {
    RESERVED_KEYS = new HashMap<String, AggregatedLogFormat.LogKey>();
    RESERVED_KEYS.put(APPLICATION_ACL_KEY.toString(), APPLICATION_ACL_KEY);
    RESERVED_KEYS.put(APPLICATION_OWNER_KEY.toString(), APPLICATION_OWNER_KEY);
    RESERVED_KEYS.put(VERSION_KEY.toString(), VERSION_KEY);
  }

  @Public
  public static class LogKey implements Writable {

    private String keyString;

    public LogKey() {

    }

    public LogKey(ContainerId containerId) {
      this.keyString = containerId.toString();
    }

    public LogKey(String keyString) {
      this.keyString = keyString;
    }
    
    @Override
    public int hashCode() {
      return keyString == null ? 0 : keyString.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof LogKey) {
        LogKey other = (LogKey) obj;
        if (this.keyString == null) {
          return other.keyString == null;
        }
        return this.keyString.equals(other.keyString);
      }
      return false;
    }

    @Private
    @Override
    public void write(DataOutput out) throws IOException {
      out.writeUTF(this.keyString);
    }

    @Private
    @Override
    public void readFields(DataInput in) throws IOException {
      this.keyString = in.readUTF();
    }

    @Override
    public String toString() {
      return this.keyString;
    }
  }

  @Private
  public static class LogValue {

    private final List<String> rootLogDirs;
    private final ContainerId containerId;
    private final String user;
    private final LogAggregationContext logAggregationContext;
    private Set<File> uploadedFiles = new HashSet<File>();
    private final Set<String> alreadyUploadedLogFiles;
    private Set<String> allExistingFileMeta = new HashSet<String>();
    private final boolean appFinished;
    private final boolean containerFinished;
    // TODO Maybe add a version string here. Instead of changing the version of
    // the entire k-v format

    public LogValue(List<String> rootLogDirs, ContainerId containerId,
        String user) {
      this(rootLogDirs, containerId, user, null, new HashSet<String>(), true,
          true);
    }

    public LogValue(List<String> rootLogDirs, ContainerId containerId,
        String user, LogAggregationContext logAggregationContext,
        Set<String> alreadyUploadedLogFiles, boolean appFinished,
        boolean containerFinished) {
      this.rootLogDirs = new ArrayList<String>(rootLogDirs);
      this.containerId = containerId;
      this.user = user;

      // Ensure logs are processed in lexical order
      Collections.sort(this.rootLogDirs);
      this.logAggregationContext = logAggregationContext;
      this.alreadyUploadedLogFiles = alreadyUploadedLogFiles;
      this.appFinished = appFinished;
      this.containerFinished = containerFinished;
    }

    private Set<File> getPendingLogFilesToUploadForThisContainer() {
      Set<File> pendingUploadFiles = new HashSet<File>();
      for (String rootLogDir : this.rootLogDirs) {
        File appLogDir =
            new File(rootLogDir, 
                ConverterUtils.toString(
                    this.containerId.getApplicationAttemptId().
                        getApplicationId())
                );
        File containerLogDir =
            new File(appLogDir, ConverterUtils.toString(this.containerId));

        if (!containerLogDir.isDirectory()) {
          continue; // ContainerDir may have been deleted by the user.
        }

        pendingUploadFiles
          .addAll(getPendingLogFilesToUpload(containerLogDir));
      }
      return pendingUploadFiles;
    }

    public void write(DataOutputStream out, Set<File> pendingUploadFiles)
        throws IOException {
      List<File> fileList = new ArrayList<File>(pendingUploadFiles);
      Collections.sort(fileList);

      for (File logFile : fileList) {
        // We only aggregate top level files.
        // Ignore anything inside sub-folders.
        if (logFile.isDirectory()) {
          LOG.warn(logFile.getAbsolutePath() + " is a directory. Ignore it.");
          continue;
        }

        FileInputStream in = null;
        try {
          in = secureOpenFile(logFile);
        } catch (IOException e) {
          logErrorMessage(logFile, e);
          IOUtils.cleanup(LOG, in);
          continue;
        }

        final long fileLength = logFile.length();
        // Write the logFile Type
        out.writeUTF(logFile.getName());

        // Write the log length as UTF so that it is printable
        out.writeUTF(String.valueOf(fileLength));

        // Write the log itself
        try {
          byte[] buf = new byte[65535];
          int len = 0;
          long bytesLeft = fileLength;
          while ((len = in.read(buf)) != -1) {
            //If buffer contents within fileLength, write
            if (len < bytesLeft) {
              out.write(buf, 0, len);
              bytesLeft-=len;
            }
            //else only write contents within fileLength, then exit early
            else {
              out.write(buf, 0, (int)bytesLeft);
              break;
            }
          }
          long newLength = logFile.length();
          if(fileLength < newLength) {
            LOG.warn("Aggregated logs truncated by approximately "+
                (newLength-fileLength) +" bytes.");
          }
          this.uploadedFiles.add(logFile);
        } catch (IOException e) {
          String message = logErrorMessage(logFile, e);
          out.write(message.getBytes(Charset.forName("UTF-8")));
        } finally {
          IOUtils.cleanup(LOG, in);
        }
      }
    }

    @VisibleForTesting
    public FileInputStream secureOpenFile(File logFile) throws IOException {
      return SecureIOUtils.openForRead(logFile, getUser(), null);
    }

    private static String logErrorMessage(File logFile, Exception e) {
      String message = "Error aggregating log file. Log file : "
          + logFile.getAbsolutePath() + ". " + e.getMessage();
      LOG.error(message, e);
      return message;
    }

    // Added for testing purpose.
    public String getUser() {
      return user;
    }

    private Set<File> getPendingLogFilesToUpload(File containerLogDir) {
      Set<File> candidates =
          new HashSet<File>(Arrays.asList(containerLogDir.listFiles()));
      for (File logFile : candidates) {
        this.allExistingFileMeta.add(getLogFileMetaData(logFile));
      }

      Set<File> fileCandidates = new HashSet<File>(candidates);
      if (this.logAggregationContext != null && candidates.size() > 0) {
        fileCandidates = getFileCandidates(fileCandidates, this.appFinished);
        if (!this.appFinished && this.containerFinished) {
          Set<File> addition = new HashSet<File>(candidates);
          addition = getFileCandidates(addition, true);
          fileCandidates.addAll(addition);
        }
      }
      return fileCandidates;
    }

    private Set<File> getFileCandidates(Set<File> candidates,
        boolean useRegularPattern) {
      filterFiles(
          useRegularPattern ? this.logAggregationContext.getIncludePattern()
              : this.logAggregationContext.getRolledLogsIncludePattern(),
          candidates, false);

      filterFiles(
          useRegularPattern ? this.logAggregationContext.getExcludePattern()
              : this.logAggregationContext.getRolledLogsExcludePattern(),
          candidates, true);

      Iterable<File> mask =
          Iterables.filter(candidates, new Predicate<File>() {
            @Override
            public boolean apply(File next) {
              return !alreadyUploadedLogFiles
                  .contains(getLogFileMetaData(next));
            }
          });
      return Sets.newHashSet(mask);
    }

    private void filterFiles(String pattern, Set<File> candidates,
        boolean exclusion) {
      if (pattern != null && !pattern.isEmpty()) {
        Pattern filterPattern = Pattern.compile(pattern);
        for (Iterator<File> candidatesItr = candidates.iterator(); candidatesItr
          .hasNext();) {
          File candidate = candidatesItr.next();
          boolean match = filterPattern.matcher(candidate.getName()).find();
          if ((!match && !exclusion) || (match && exclusion)) {
            candidatesItr.remove();
          }
        }
      }
    }

    public Set<Path> getCurrentUpLoadedFilesPath() {
      Set<Path> path = new HashSet<Path>();
      for (File file : this.uploadedFiles) {
        path.add(new Path(file.getAbsolutePath()));
      }
      return path;
    }

    public Set<String> getCurrentUpLoadedFileMeta() {
      Set<String> info = new HashSet<String>();
      for (File file : this.uploadedFiles) {
        info.add(getLogFileMetaData(file));
      }
      return info;
    }

    public Set<String> getAllExistingFilesMeta() {
      return this.allExistingFileMeta;
    }

    private String getLogFileMetaData(File file) {
      return containerId.toString() + "_" + file.getName() + "_"
          + file.lastModified();
    }
  }

  /**
   * The writer that writes out the aggregated logs.
   */
  @Private
  public static class LogWriter {

    private final FSDataOutputStream fsDataOStream;
    private final TFile.Writer writer;
    private FileContext fc;

    public LogWriter(final Configuration conf, final Path remoteAppLogFile,
        UserGroupInformation userUgi) throws IOException {
      try {
        this.fsDataOStream =
            userUgi.doAs(new PrivilegedExceptionAction<FSDataOutputStream>() {
              @Override
              public FSDataOutputStream run() throws Exception {
                fc = FileContext.getFileContext(remoteAppLogFile.toUri(), conf);
                fc.setUMask(APP_LOG_FILE_UMASK);
                return fc.create(
                    remoteAppLogFile,
                    EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE),
                    new Options.CreateOpts[] {});
              }
            });
      } catch (InterruptedException e) {
        throw new IOException(e);
      }

      // Keys are not sorted: null arg
      // 256KB minBlockSize : Expected log size for each container too
      this.writer =
          new TFile.Writer(this.fsDataOStream, 256 * 1024, conf.get(
              YarnConfiguration.NM_LOG_AGG_COMPRESSION_TYPE,
              YarnConfiguration.DEFAULT_NM_LOG_AGG_COMPRESSION_TYPE), null, conf);
      //Write the version string
      writeVersion();
    }

    @VisibleForTesting
    public TFile.Writer getWriter() {
      return this.writer;
    }

    private void writeVersion() throws IOException {
      DataOutputStream out = this.writer.prepareAppendKey(-1);
      VERSION_KEY.write(out);
      out.close();
      out = this.writer.prepareAppendValue(-1);
      out.writeInt(VERSION);
      out.close();
    }

    public void writeApplicationOwner(String user) throws IOException {
      DataOutputStream out = this.writer.prepareAppendKey(-1);
      APPLICATION_OWNER_KEY.write(out);
      out.close();
      out = this.writer.prepareAppendValue(-1);
      out.writeUTF(user);
      out.close();
    }

    public void writeApplicationACLs(Map<ApplicationAccessType, String> appAcls)
        throws IOException {
      DataOutputStream out = this.writer.prepareAppendKey(-1);
      APPLICATION_ACL_KEY.write(out);
      out.close();
      out = this.writer.prepareAppendValue(-1);
      for (Entry<ApplicationAccessType, String> entry : appAcls.entrySet()) {
        out.writeUTF(entry.getKey().toString());
        out.writeUTF(entry.getValue());
      }
      out.close();
    }

    public void append(LogKey logKey, LogValue logValue) throws IOException {
      Set<File> pendingUploadFiles =
          logValue.getPendingLogFilesToUploadForThisContainer();
      if (pendingUploadFiles.size() == 0) {
        return;
      }
      DataOutputStream out = this.writer.prepareAppendKey(-1);
      logKey.write(out);
      out.close();
      out = this.writer.prepareAppendValue(-1);
      logValue.write(out, pendingUploadFiles);
      out.close();
    }

    public void close() {
      try {
        this.writer.close();
      } catch (IOException e) {
        LOG.warn("Exception closing writer", e);
      }
      IOUtils.closeStream(fsDataOStream);
    }
  }

  @Public
  @Evolving
  public static class LogReader {

    private final FSDataInputStream fsDataIStream;
    private final TFile.Reader.Scanner scanner;
    private final TFile.Reader reader;

    public LogReader(Configuration conf, Path remoteAppLogFile)
        throws IOException {
      FileContext fileContext =
          FileContext.getFileContext(remoteAppLogFile.toUri(), conf);
      this.fsDataIStream = fileContext.open(remoteAppLogFile);
      reader =
          new TFile.Reader(this.fsDataIStream, fileContext.getFileStatus(
              remoteAppLogFile).getLen(), conf);
      this.scanner = reader.createScanner();
    }

    private boolean atBeginning = true;

    /**
     * Returns the owner of the application.
     * 
     * @return the application owner.
     * @throws IOException
     */
    public String getApplicationOwner() throws IOException {
      TFile.Reader.Scanner ownerScanner = reader.createScanner();
      LogKey key = new LogKey();
      while (!ownerScanner.atEnd()) {
        TFile.Reader.Scanner.Entry entry = ownerScanner.entry();
        key.readFields(entry.getKeyStream());
        if (key.toString().equals(APPLICATION_OWNER_KEY.toString())) {
          DataInputStream valueStream = entry.getValueStream();
          return valueStream.readUTF();
        }
        ownerScanner.advance();
      }
      return null;
    }

    /**
     * Returns ACLs for the application. An empty map is returned if no ACLs are
     * found.
     * 
     * @return a map of the Application ACLs.
     * @throws IOException
     */
    public Map<ApplicationAccessType, String> getApplicationAcls()
        throws IOException {
      // TODO Seek directly to the key once a comparator is specified.
      TFile.Reader.Scanner aclScanner = reader.createScanner();
      LogKey key = new LogKey();
      Map<ApplicationAccessType, String> acls =
          new HashMap<ApplicationAccessType, String>();
      while (!aclScanner.atEnd()) {
        TFile.Reader.Scanner.Entry entry = aclScanner.entry();
        key.readFields(entry.getKeyStream());
        if (key.toString().equals(APPLICATION_ACL_KEY.toString())) {
          DataInputStream valueStream = entry.getValueStream();
          while (true) {
            String appAccessOp = null;
            String aclString = null;
            try {
              appAccessOp = valueStream.readUTF();
            } catch (EOFException e) {
              // Valid end of stream.
              break;
            }
            try {
              aclString = valueStream.readUTF();
            } catch (EOFException e) {
              throw new YarnRuntimeException("Error reading ACLs", e);
            }
            acls.put(ApplicationAccessType.valueOf(appAccessOp), aclString);
          }

        }
        aclScanner.advance();
      }
      return acls;
    }
    
    /**
     * Read the next key and return the value-stream.
     * 
     * @param key
     * @return the valueStream if there are more keys or null otherwise.
     * @throws IOException
     */
    public DataInputStream next(LogKey key) throws IOException {
      if (!this.atBeginning) {
        this.scanner.advance();
      } else {
        this.atBeginning = false;
      }
      if (this.scanner.atEnd()) {
        return null;
      }
      TFile.Reader.Scanner.Entry entry = this.scanner.entry();
      key.readFields(entry.getKeyStream());
      // Skip META keys
      if (RESERVED_KEYS.containsKey(key.toString())) {
        return next(key);
      }
      DataInputStream valueStream = entry.getValueStream();
      return valueStream;
    }

    /**
     * Get a ContainerLogsReader to read the logs for
     * the specified container.
     *
     * @param containerId
     * @return object to read the container's logs or null if the
     *         logs could not be found
     * @throws IOException
     */
    @Private
    public ContainerLogsReader getContainerLogsReader(
        ContainerId containerId) throws IOException {
      ContainerLogsReader logReader = null;

      final LogKey containerKey = new LogKey(containerId);
      LogKey key = new LogKey();
      DataInputStream valueStream = next(key);
      while (valueStream != null && !key.equals(containerKey)) {
        valueStream = next(key);
      }

      if (valueStream != null) {
        logReader = new ContainerLogsReader(valueStream);
      }

      return logReader;
    }

    //TODO  Change Log format and interfaces to be containerId specific.
    // Avoid returning completeValueStreams.
//    public List<String> getTypesForContainer(DataInputStream valueStream){}
//    
//    /**
//     * @param valueStream
//     *          The Log stream for the container.
//     * @param fileType
//     *          the log type required.
//     * @return An InputStreamReader for the required log type or null if the
//     *         type is not found.
//     * @throws IOException
//     */
//    public InputStreamReader getLogStreamForType(DataInputStream valueStream,
//        String fileType) throws IOException {
//      valueStream.reset();
//      try {
//        while (true) {
//          String ft = valueStream.readUTF();
//          String fileLengthStr = valueStream.readUTF();
//          long fileLength = Long.parseLong(fileLengthStr);
//          if (ft.equals(fileType)) {
//            BoundedInputStream bis =
//                new BoundedInputStream(valueStream, fileLength);
//            return new InputStreamReader(bis);
//          } else {
//            long totalSkipped = 0;
//            long currSkipped = 0;
//            while (currSkipped != -1 && totalSkipped < fileLength) {
//              currSkipped = valueStream.skip(fileLength - totalSkipped);
//              totalSkipped += currSkipped;
//            }
//            // TODO Verify skip behaviour.
//            if (currSkipped == -1) {
//              return null;
//            }
//          }
//        }
//      } catch (EOFException e) {
//        return null;
//      }
//    }

    /**
     * Writes all logs for a single container to the provided writer.
     * @param valueStream
     * @param writer
     * @param logUploadedTime
     * @throws IOException
     */
    public static void readAcontainerLogs(DataInputStream valueStream,
        Writer writer, long logUploadedTime) throws IOException {
      OutputStream os = null;
      PrintStream ps = null;
      try {
        os = new WriterOutputStream(writer, Charset.forName("UTF-8"));
        ps = new PrintStream(os);
        while (true) {
          try {
            readContainerLogs(valueStream, ps, logUploadedTime, Long.MAX_VALUE);
          } catch (EOFException e) {
            // EndOfFile
            return;
          }
        }
      } finally {
        IOUtils.cleanup(LOG, ps);
        IOUtils.cleanup(LOG, os);
      }
    }

    /**
     * Writes all logs for a single container to the provided writer.
     * @param valueStream
     * @param writer
     * @throws IOException
     */
    public static void readAcontainerLogs(DataInputStream valueStream,
        Writer writer) throws IOException {
      readAcontainerLogs(valueStream, writer, -1);
    }

    private static void readContainerLogs(DataInputStream valueStream,
        PrintStream out, long logUploadedTime, long bytes)
        throws IOException {
      byte[] buf = new byte[65535];

      String fileType = valueStream.readUTF();
      String fileLengthStr = valueStream.readUTF();
      long fileLength = Long.parseLong(fileLengthStr);
      out.print("LogType:");
      out.println(fileType);
      if (logUploadedTime != -1) {
        out.print("Log Upload Time:");
        out.println(Times.format(logUploadedTime));
      }
      out.print("LogLength:");
      out.println(fileLengthStr);
      out.println("Log Contents:");

      long toSkip = 0;
      long totalBytesToRead = fileLength;
      if (bytes < 0) {
        long absBytes = Math.abs(bytes);
        if (absBytes < fileLength) {
          toSkip = fileLength - absBytes;
          totalBytesToRead = absBytes;
        }
        long skippedBytes = valueStream.skip(toSkip);
        if (skippedBytes != toSkip) {
          throw new IOException("The bytes were skipped are "
              + "different from the caller requested");
        }
      } else {
        if (bytes < fileLength) {
          totalBytesToRead = bytes;
        }
      }

      long curRead = 0;
      long pendingRead = totalBytesToRead - curRead;
      int toRead =
                pendingRead > buf.length ? buf.length : (int) pendingRead;
      int len = valueStream.read(buf, 0, toRead);
      while (len != -1 && curRead < totalBytesToRead) {
        out.write(buf, 0, len);
        curRead += len;

        pendingRead = totalBytesToRead - curRead;
        toRead =
                  pendingRead > buf.length ? buf.length : (int) pendingRead;
        len = valueStream.read(buf, 0, toRead);
      }
      out.println("End of LogType:" + fileType);
      out.println("");
    }

    /**
     * Keep calling this till you get a {@link EOFException} for getting logs of
     * all types for a single container.
     * 
     * @param valueStream
     * @param out
     * @param logUploadedTime
     * @throws IOException
     */
    public static void readAContainerLogsForALogType(
        DataInputStream valueStream, PrintStream out, long logUploadedTime)
          throws IOException {
      readContainerLogs(valueStream, out, logUploadedTime, Long.MAX_VALUE);
    }

    /**
     * Keep calling this till you get a {@link EOFException} for getting logs of
     * all types for a single container for the specific bytes.
     *
     * @param valueStream
     * @param out
     * @param logUploadedTime
     * @param bytes
     * @throws IOException
     */
    public static void readAContainerLogsForALogType(
        DataInputStream valueStream, PrintStream out, long logUploadedTime,
        long bytes) throws IOException {
      readContainerLogs(valueStream, out, logUploadedTime, bytes);
    }

    /**
     * Keep calling this till you get a {@link EOFException} for getting logs of
     * all types for a single container.
     * 
     * @param valueStream
     * @param out
     * @throws IOException
     */
    public static void readAContainerLogsForALogType(
        DataInputStream valueStream, PrintStream out)
          throws IOException {
      readAContainerLogsForALogType(valueStream, out, -1);
    }

    /**
     * Keep calling this till you get a {@link EOFException} for getting logs of
     * the specific types for a single container.
     * @param valueStream
     * @param out
     * @param logUploadedTime
     * @param logType
     * @throws IOException
     */
    public static int readContainerLogsForALogType(
        DataInputStream valueStream, PrintStream out, long logUploadedTime,
        List<String> logType) throws IOException {
      return readContainerLogsForALogType(valueStream, out, logUploadedTime,
          logType, Long.MAX_VALUE);
    }

    /**
     * Keep calling this till you get a {@link EOFException} for getting logs of
     * the specific types for a single container.
     * @param valueStream
     * @param out
     * @param logUploadedTime
     * @param logType
     * @throws IOException
     */
    public static int readContainerLogsForALogType(
        DataInputStream valueStream, PrintStream out, long logUploadedTime,
        List<String> logType, long bytes) throws IOException {
      byte[] buf = new byte[65535];

      String fileType = valueStream.readUTF();
      String fileLengthStr = valueStream.readUTF();
      long fileLength = Long.parseLong(fileLengthStr);
      if (logType.contains(fileType)) {
        out.print("LogType:");
        out.println(fileType);
        if (logUploadedTime != -1) {
          out.print("Log Upload Time:");
          out.println(Times.format(logUploadedTime));
        }
        out.print("LogLength:");
        out.println(fileLengthStr);
        out.println("Log Contents:");

        long toSkip = 0;
        long totalBytesToRead = fileLength;
        if (bytes < 0) {
          long absBytes = Math.abs(bytes);
          if (absBytes < fileLength) {
            toSkip = fileLength - absBytes;
            totalBytesToRead = absBytes;
          }
          long skippedBytes = valueStream.skip(toSkip);
          if (skippedBytes != toSkip) {
            throw new IOException("The bytes were skipped are "
                + "different from the caller requested");
          }
        } else {
          if (bytes < fileLength) {
            totalBytesToRead = bytes;
          }
        }

        long curRead = 0;
        long pendingRead = totalBytesToRead - curRead;
        int toRead = pendingRead > buf.length ? buf.length : (int) pendingRead;
        int len = valueStream.read(buf, 0, toRead);
        while (len != -1 && curRead < totalBytesToRead) {
          out.write(buf, 0, len);
          curRead += len;

          pendingRead = totalBytesToRead - curRead;
          toRead = pendingRead > buf.length ? buf.length : (int) pendingRead;
          len = valueStream.read(buf, 0, toRead);
        }
        out.println("End of LogType:" + fileType);
        out.println("");
        return 0;
      } else {
        long totalSkipped = 0;
        long currSkipped = 0;
        while (currSkipped != -1 && totalSkipped < fileLength) {
          currSkipped = valueStream.skip(fileLength - totalSkipped);
          totalSkipped += currSkipped;
        }
        return -1;
      }
    }

    @Private
    public static String readContainerMetaDataAndSkipData(
        DataInputStream valueStream, PrintStream out) throws IOException {

      String fileType = valueStream.readUTF();
      String fileLengthStr = valueStream.readUTF();
      long fileLength = Long.parseLong(fileLengthStr);
      if (out != null) {
        out.print("LogType:");
        out.println(fileType);
        out.print("LogLength:");
        out.println(fileLengthStr);
      }
      long totalSkipped = 0;
      long currSkipped = 0;
      while (currSkipped != -1 && totalSkipped < fileLength) {
        currSkipped = valueStream.skip(fileLength - totalSkipped);
        totalSkipped += currSkipped;
      }
      return fileType;
    }

    public void close() {
      IOUtils.cleanup(LOG, scanner, reader, fsDataIStream);
    }
  }

  @Private
  public static class ContainerLogsReader {
    private DataInputStream valueStream;
    private String currentLogType = null;
    private long currentLogLength = 0;
    private BoundedInputStream currentLogData = null;
    private InputStreamReader currentLogISR;

    public ContainerLogsReader(DataInputStream stream) {
      valueStream = stream;
    }

    public String nextLog() throws IOException {
      if (currentLogData != null && currentLogLength > 0) {
        // seek to the end of the current log, relying on BoundedInputStream
        // to prevent seeking past the end of the current log
        do {
          if (currentLogData.skip(currentLogLength) < 0) {
            break;
          }
        } while (currentLogData.read() != -1);
      }

      currentLogType = null;
      currentLogLength = 0;
      currentLogData = null;
      currentLogISR = null;

      try {
        String logType = valueStream.readUTF();
        String logLengthStr = valueStream.readUTF();
        currentLogLength = Long.parseLong(logLengthStr);
        currentLogData =
            new BoundedInputStream(valueStream, currentLogLength);
        currentLogData.setPropagateClose(false);
        currentLogISR = new InputStreamReader(currentLogData,
            Charset.forName("UTF-8"));
        currentLogType = logType;
      } catch (EOFException e) {
      }

      return currentLogType;
    }

    public String getCurrentLogType() {
      return currentLogType;
    }

    public long getCurrentLogLength() {
      return currentLogLength;
    }

    public long skip(long n) throws IOException {
      return currentLogData.skip(n);
    }

    public int read() throws IOException {
      return currentLogData.read();
    }

    public int read(byte[] buf, int off, int len) throws IOException {
      return currentLogData.read(buf, off, len);
    }

    public int read(char[] buf, int off, int len) throws IOException {
      return currentLogISR.read(buf, off, len);
    }
  }
}

package gov.ca.cwds.jobs.common.job.timestamp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.FileUtils;

/**
 * Created by Alexander Serbin on 2/14/2018.
 */
public class LastRunDirHelper {

  private String folder;

  public LastRunDirHelper(String folder) {
    this.folder = folder;
  }

  public void createTimestampDirectory() throws IOException {
    FileUtils.forceMkdir(getLastRunDir().toFile());
  }

  public void deleteTimestampDirectory() throws IOException {
    FileUtils.deleteDirectory(getLastRunDir().toFile());
  }

  @SuppressFBWarnings("PATH_TRAVERSAL_IN") //Path cannot be controlled by the user
  public Path getLastRunDir() {
    return Paths.get(String.valueOf(folder)).normalize().toAbsolutePath();
  }

}

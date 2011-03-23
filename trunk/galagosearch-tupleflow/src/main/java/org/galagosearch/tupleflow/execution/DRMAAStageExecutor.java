//BSD License (http://www.galagosearch.org/license)
package org.galagosearch.tupleflow.execution;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ggf.drmaa.DrmaaException;
import org.ggf.drmaa.JobTemplate;
import org.ggf.drmaa.Session;
import org.ggf.drmaa.SessionFactory;

/**
 * DRMAAExecutor
 *
 * March 30, 2007 -- Trevor Strohman
 * March 31, 2010 -- Sam Huston, Stanford Chiu
 * Sept 16,  2010 -- irmarc
 *    - Added code for getting timing results
 *
 * Usage : StageExecutionFactory
 *  - when a user submits a job with mode="drmaa"
 *
 * - Keeps track of all stage instances submitted to the drmaa api
 * - Provides some statistics on the running stages
 *
 * @author trevor, sjh, schiu, irmarc
 */
public class DRMAAStageExecutor extends RemoteStageExecutor {

  Session session;
  // Flag to set the verbose mode (either on or off)
  public boolean verbose;
  // For use with user-defined native specifications
  public boolean addNativeSpecification;
  public String nativeSpecification_short;
  public String nativeSpecification_long;
  // Use the 'java' specified in the env. variable JAVA_HOME --
  // we don't know what version of java the user called us with :(
  public String command = System.getenv("JAVA_HOME") + File.separator
          + "bin/java";
  // We do know what class path the user envoked us with, so use that
  // when submitting each of the jobs to the cluster.
  public String classPath = System.getProperty("java.class.path");
  // This is the TupleFlow executor we'll be using for each of the jobs.
  public String className = "org.galagosearch.tupleflow.execution.LocalStageExecutor";
  // Arbitrary starting and max heap sizes.
  public static final String MEMORY_X = "-Xmx1700m";
  public static final String MEMORY_S = "-Xms1700m";
  // This holds the location that should be used to write temporary files
  // on the nodes.
  public static final String NODE_TEMP_DIR =
          System.getProperty("java.io.tmpdir");
  // These will hold the starting and max heaps passed in by the executor
  // calling us, if any.
  public String memory_x;
  public String memory_s;
  public String nodeTempDir;

  public class DRMAAResult implements StageExecutionStatus {

    ArrayList<String> jobs;
    HashMap<String, Long> startTimes = null;
    HashMap<String, Long> stopTimes = null;
    ArrayList<Exception> exceptions;
    String stageName;

    public DRMAAResult(String n, ArrayList<String> jobs, HashMap<String, Long> starts) {
      this(n, jobs, starts, null);
    }

    public DRMAAResult(String n, ArrayList<String> jobs, HashMap<String, Long> starts, Exception e) {
      this.jobs = jobs;
      this.startTimes = starts;
      this.stopTimes = new HashMap<String, Long>();
      this.stageName = n;
      this.exceptions = new ArrayList<Exception>();
      if (e != null) {
        this.exceptions.add(e);
      }
    }

    public ArrayList<Exception> getExceptions() {

      for (String job : jobs) {
        try {
          int status = session.getJobProgramStatus(job);
          if (status == session.FAILED) {
            exceptions.add(new Exception("[" + job + "] failed."));
            System.err.println("[" + job + "] failed.");
          }
        } catch (DrmaaException e) {
          System.err.println("Could not error check the drmaa session!");
        }
      }
      return exceptions;
    }

    public String getName() {
      return stageName;
    }

    public int getCompletedInstances() {
      int comp = 0;
      try {
        for (String job : jobs) {
          int status = session.getJobProgramStatus(job);

          //if( status == session.FAILED )
          //System.err.println( "[" + job + "] failed." );

          if (status == session.DONE || status == session.FAILED) {
            comp += 1;
          }
        }
      } catch (DrmaaException e) {
        return 0;
      }

      return comp;
    }

    public int getRunningInstances() {
      int comp = 0;
      try {
        for (String job : jobs) {
          int status = session.getJobProgramStatus(job);

          comp += 1;

          //if( status == session.FAILED )
          //System.err.println( "[" + job + "] failed." );

          if (status == session.QUEUED_ACTIVE || status == session.DONE || status == session.FAILED) {
            comp -= 1;
          }
        }
      } catch (DrmaaException e) {
        return 0;
      }

      return comp;
    }

    public int getQueuedInstances() {
      int queued = 0;
      try {
        for (String job : jobs) {
          int status = session.getJobProgramStatus(job);

          if (status == session.QUEUED_ACTIVE) {
            queued += 1;
          }
        }
      } catch (DrmaaException e) {
        return 0;
      }

      return queued;
    }

    public int getBlockedInstances() {
      return 0;
    }

    public synchronized List<Double> getRunTimes() {
      ArrayList<Double> times = new ArrayList();
      long current;
      if (startTimes != null) {
        for (String jobid : startTimes.keySet()) {
          try {
            int status = session.getJobProgramStatus(jobid);
            long start = startTimes.get(jobid);
            if (status == session.DONE || status == session.FAILED) {
              if (!stopTimes.containsKey(jobid)) {
                stopTimes.put(jobid, System.currentTimeMillis());
              }
              current = stopTimes.get(jobid);
            } else {
              current = System.currentTimeMillis();
            }
            double diff = (current - start + 0.0) / 1000.0;
            times.add(diff);
          } catch (DrmaaException e) {
          }
        }
      }
      return times;
    }

    public boolean isDone() {
      try {
        for (String job : jobs) {
          int status = session.getJobProgramStatus(job);

          //if( status == session.FAILED )
          //System.err.println( "[" + job + "] failed." );

          if (status == session.DONE || status == session.FAILED) {
            continue;
          }

          return false;
        }
      } catch (DrmaaException e) {
        return false;
      }

      return true;
    }
  }

  /**
   * <p>Creates a new instance of DRMAAExecutor.</p>
   *
   * @param arguments     An array; if it contains anything, the first
   *                      element is used as the command when submitting
   *                      jobs to DRMAA.
   */
  public DRMAAStageExecutor(String[] args) {

    // Set the defaults for each job.
    setMemoryUsage(MEMORY_X, MEMORY_S);
    nodeTempDir = NODE_TEMP_DIR;
    verbose = false;
    addNativeSpecification = false;
    nativeSpecification_short = "";
    nativeSpecification_long = "";

    // CIIR specific parameters
    // First get the hostname
    String hostname;
    try {
      InetAddress local = InetAddress.getLocalHost();
      hostname = local.getHostName();
    } catch (UnknownHostException ex) {
      hostname = "localhost";
    }
    if (hostname.contains("swarm")) {
      setMemoryUsage("-Xmx4g", "-Xms4g");
      addNativeSpecification = true;
      nativeSpecification_short = "-l mem_free=4G -w n";
      nativeSpecification_long = "-l long=TRUE -l mem_free=4G -w n";
    } else if (hostname.contains("sydney")) {
      setMemoryUsage("-Xmx3500m", "-Xms3500m");
      addNativeSpecification = true;
      nativeSpecification_short = "-l mem_free=3700mb -w n";
      nativeSpecification_long = "-l mem_free=3700mb -w n";
    }

    // customize based upon arguments

    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("Xmx")) {
        memory_x = "-" + args[i];
      } else if (args[i].startsWith("Xms")) {
        memory_s = "-" + args[i];
      } else if (args[i].startsWith("-t=")) {
        nodeTempDir = args[i].replace("-t=", "");
      } else if (args[i].startsWith("-v")) {
        verbose = true;
      } else if (args[i].startsWith("-ns=")) {
        addNativeSpecification = true;
        nativeSpecification_short = args[i].replaceAll("^-ns=", "");
        nativeSpecification_short.replaceAll("(^\")|(\"$)", "");
        nativeSpecification_long = nativeSpecification_short;
      } else {
        System.out.println("Ignoring unknown argument: " + args[i]);
      }
    }

    try {
      session = SessionFactory.getFactory().getSession();
      session.init("");
    } catch (DrmaaException e) {
    }
  }

  // dont need these function anymore....
  /**
   * <p>Sets the verbose mode.</p>
   *
   * @param verbose   The verbose mode.
   */
  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  /**
   * <p>Sets a native specification.</p>
   *
   * @param nativeSpecification The specification to set.
   */
  public void setNativeSpecification(String nativeSpecification) {
    this.addNativeSpecification = true;
    this.nativeSpecification_short = nativeSpecification;
    this.nativeSpecification_long = nativeSpecification;
  }

  /**
   * <p>Sets the initial heap space (<code>memory_s</code>) and the
   * maximum heap space (<code>memory_x</code>). See the values of
   * <code>MEMORY_X</code> and <code>MEMORY_S</code> for the defaults. </p>
   *
   * @param memory_x  The max heap space to use.
   * @param memory_s  The initial heap space to use.
   */
  public void setMemoryUsage(String memory_x, String memory_s) {
    this.memory_x = memory_x;
    this.memory_s = memory_s;
  }

  /**
   * <p>Sets the temporary directory to use for scratch space on each of the
   * nodes.</p>
   *
   * @param nodeTempDir   The path to the temporary directory.
   */
  public void setNodeTempDir(String nodeTempDir) {
    this.nodeTempDir = nodeTempDir;
  }

  public void shutdown() {
    try {
      session.exit();
    } catch (DrmaaException e) {
    }
  }

  /**
   * <p>Submit all of the jobs for the given stage to the cluster.</p>
   *
   * @param stageName     The stage whose jobs are being submitted.
   * @param jobPaths      The path to each of the jobs being submitted.
   * @param temporary     The path to the temporary directory to use. This
   *                      is where the stdout/stderr files will be stored.
   *
   * @return The results of the jobs and eny errors that were thrown.
   */
  public StageExecutionStatus submit(String stageName, ArrayList<String> jobPaths,
          String temporary) {
    ArrayList<String> jobs = new ArrayList<String>();
    HashMap<String, Long> startTimes = new HashMap<String, Long>();
    try {
      // Cycle through each of the jobs for the given stage.
      for (int i = 0; i < jobPaths.size(); i++) {
        // Fill in the arguments to Java. These include the starting/max
        // heap space, the class path, the executor to call, and the
        // path to the job to run.
        String[] arguments = new String[]{"-ea", memory_x, memory_s,
          "-Djava.io.tmpdir=" + nodeTempDir,
          "-cp", classPath, className, jobPaths.get(i)};


        // Create the fill a DRMAA job template.
        JobTemplate template = session.createJobTemplate();
        template.setJobName("galago-" + stageName + "-" + i);
        template.setRemoteCommand(command);
        template.setArgs(arguments);
        template.setOutputPath(":" + temporary + File.separator
                + "stdout");
        template.setErrorPath(":" + temporary + File.separator
                + "stderr");

        // If the user wants the jobs submitted to a particular
        // queue, set that here.
        if (addNativeSpecification) {
          if (jobPaths.size() == 1) {
            // if there's only one job - long queue
            if(nativeSpecification_long.length() > 0){
              template.setNativeSpecification(nativeSpecification_long);
            }
          } else {
            // otherwise use the shortt queue
            if(nativeSpecification_short.length() > 0){
            template.setNativeSpecification(nativeSpecification_short);
            }
          }
        }

        // This will print the submitted command. (It gets kind of long
        // because of the class path and the absolute paths of the
        // job files.) We should probably have a switch for this...
        if (verbose) {
          System.err.print("Running: " + command);
          for (int index = 0; index < arguments.length; index++) {
            System.err.print(" " + arguments[index]);
          }
          System.err.println("");
        }


        // Run the job.
        String id = session.runJob(template);
        int status = session.getJobProgramStatus(id);
        if (status == Session.FAILED) {
          System.err.println("ERROR: Job failed! [" + stageName
                  + "-" + i + "]");
        }

        // Keep track of the job id.
        jobs.add(id);
        startTimes.put(id, System.currentTimeMillis()); // for tracking

        // Clean up.
        session.deleteJobTemplate(template);
      }
    } catch (Exception e) {
      System.err.println("Problems submitting jobs: " + e.getMessage());
      return new DRMAAResult(stageName, jobs, null, e);
    }

    System.err.println("job-launched: " + stageName);
    for (String id : jobs) {
      System.err.println("jobid: " + id);
    }
    return new DRMAAResult(stageName, jobs, startTimes);
  }
}

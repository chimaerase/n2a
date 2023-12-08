/*
Copyright 2018-2023 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.neuroml;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.host.Host;
import gov.sandia.n2a.host.Host.AnyProcess;
import gov.sandia.n2a.host.Host.AnyProcessBuilder;
import gov.sandia.n2a.plugins.extpoints.Backend;

public class BackendNeuroML extends Backend
{
    @Override
    public String getName ()
    {
        return "LEMS";  // This is the name of the target simulator. NeuroML is an interchange format, not a simulator.
    }

    @Override
    public void start (MNode job)
    {
        Thread simulationThread = new SimulationThread (job);
        simulationThread.setDaemon (true);
        simulationThread.start ();
    }

    public class SimulationThread extends Thread
    {
        public MNode  job;
        public String target;

        public SimulationThread (MNode job)
        {
            super ("NeuroML Simulation");
            this.job = job;
        }

        public void run ()
        {
            Path localJobDir = Host.getJobDir (Host.getLocalResourceDir (), job);
            Path errPath = localJobDir.resolve ("err");
            try {err.set (new PrintStream (new FileOutputStream (errPath.toFile (), true), false, "UTF-8"));}
            catch (Exception e) {}

            try
            {
                job.set ("Preparing", "status");
                job.set (System.currentTimeMillis (), "started");

                // Export the model to NeuroML
                String inherit = job.get ("$inherit").replace ("\"", "");
                MNode doc = AppData.docs.child ("models", inherit);  // doc is the NON-collated model. We ignore the collated model stored in the job dir, because ExportJob will do its own collation.

                Host env = Host.get (job);
                Path jobDir = Host.getJobDir (env.getResourceDir (), job);
                Path modelPath = jobDir.resolve ("model.nml");
                ExportJob exportJob = PluginNeuroML.exporter.process (doc, modelPath, true);

                // Record metadata
                if (! exportJob.duration.isEmpty ()) job.set (exportJob.duration, "duration");
                String progress = job.get ("progress");  // The user can override defaultOutput with standard tag "progress".
                if (exportJob.simulation != null  &&  progress.isBlank ())
                {
                    List<String> outputFiles = exportJob.simulation.dumpColumns (jobDir);
                    String defaultOutput = "";
                    for (String f : outputFiles)
                    {
                        if (defaultOutput.isEmpty ()  ||  f.startsWith ("defaultOutput")) defaultOutput = f;
                    }
                    if (! defaultOutput.isEmpty ()) job.set (defaultOutput, "progress");
                }

                // Convert the model to target format using jnml

                String jnmlHome = env.config.get ("backend", "neuroml", "JNML_HOME");
                if (jnmlHome.isEmpty ()) jnmlHome = System.getenv ("JNML_HOME");  // This is unlikely to be set, but respect if it is.
                if (jnmlHome == null)    jnmlHome = "/usr/local/jNeuroML";        // just a guess
                Path jnmlHomeDir = env.getResourceDir ().getRoot ().resolve (jnmlHome);
                Path jnmlCommand = jnmlHomeDir.resolve ("jnml");

                if (target == null) target = doc.get ("$meta", "backend", "lems", "target");
                if (! target.isEmpty ()  &&  ! target.equals ("neuron"))  // NUERON gets special treatment because jnml will run it for us.
                {
                    AnyProcessBuilder b = env.build (jnmlCommand.toString (), modelPath.toString (), "-" + target);
                    Map<String,String> environment = b.environment ();
                    environment.put ("JNML_HOME", jnmlHome);

                    Path out = localJobDir.resolve ("jnml.out");
                    Path err = localJobDir.resolve ("jnml.err");
                    b.redirectOutput (out);  // Should truncate existing files.
                    b.redirectError  (err);

                    int result = 1;
                    try (AnyProcess p = b.start ())
                    {
                        p.waitFor ();
                        result = p.exitValue ();
                    }
                    catch (Exception e) {}

                    if (result != 0)
                    {
                        try
                        {
                            PrintStream os = Backend.err.get ();
                            os.println ("jnml failed:");
                            os.print (Host.streamToString (Files.newInputStream (err)));
                        }
                        catch (Exception e) {}
                    }

                    try
                    {
                        Files.delete (out);
                        Files.delete (err);
                    }
                    catch (Exception e) {}

                    if (result != 0) throw new Backend.AbortRun ();
                }

                // Close error file so external simulator can append to it.
                PrintStream ps = Backend.err.get ();
                if (ps != System.err)
                {
                    ps.close ();
                    err.remove ();
                    job.set (Host.size (errPath), "errSize");
                }

                // Run the model on the target simulator
                List<String> command = new ArrayList<String> ();
                command.add ("JNML_HOME=" + env.quote (jnmlHomeDir));
                command.add (env.quote (jnmlCommand));
                command.add (env.quote (modelPath));
                command.add ("-nogui");
                if (target.equals ("neuron"))
                {
                    command.add ("-neuron");
                    command.add ("-run");
                }
                env.submitJob (job, env.clobbersOut (), command.toArray (new String[command.size ()]));
                job.clear ("status");
            }
            catch (Exception e)
            {
                PrintStream ps = Backend.err.get ();
                if (ps == System.err)  // Need to reopen err stream.
                {
                    try {Backend.err.set (ps = new PrintStream (new FileOutputStream (errPath.toFile (), true), false, "UTF-8"));}
                    catch (Exception e2) {}
                }
                if (e instanceof AbortRun)
                {
                    String message = e.getMessage ();
                    if (message != null) ps.println (message);
                }
                else e.printStackTrace (ps);

                try {Files.copy (new ByteArrayInputStream ("failure".getBytes ("UTF-8")), localJobDir.resolve ("finished"));}
                catch (Exception f) {}
            }

            // If an exception occurred, the err file could still be open.
            PrintStream ps = err.get ();
            if (ps != System.err) ps.close ();
        }
    }
}

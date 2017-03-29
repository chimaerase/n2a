/*
Copyright 2013,2016,2017 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a;

import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.plugins.PluginManager;
import gov.sandia.n2a.ui.AboutDialog;
import gov.sandia.n2a.ui.LafManager;
import gov.sandia.n2a.ui.MainFrame;

import java.awt.EventQueue;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;

import javax.swing.JFrame;
import javax.swing.JOptionPane;


public class Main
{
    public static void main (String[] args)
    {
        setUncaughtExceptionHandler (null);

        // Set global application properties.
        AppData.properties.set ("name",         "Neurons to Algorithms");
        AppData.properties.set ("abbreviation", "N2A");
        AppData.properties.set ("version",      "0.92");
        AppData.properties.set ("developers",   "Fred Rothganger (PI), Derek Trumbo, Christy Warrender, Brad Aimone, Corinne Teeter, Brandon Rohrer, Steve Verzi, Ann Speed, Asmeret Bier, Felix Wang");
        AppData.properties.set ("copyright",    "Copyright &copy; 2013,2016 Sandia Corporation. Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation, the U.S. Government retains certain rights in this software.");
        AppData.properties.set ("license",      "This software is released under the BSD license.  Please refer to the license information provided in this distribution for the complete text.");
        AppData.properties.set ("support",      "frothga@sandia.gov");
        AppData.checkInitialDB ();

        // Load plugins
        ArrayList<String> pluginClassNames = new ArrayList<String> ();
        pluginClassNames.add ("gov.sandia.n2a.backend.internal.InternalPlugin");
        pluginClassNames.add ("gov.sandia.n2a.backend.xyce.XycePlugin");
        pluginClassNames.add ("gov.sandia.n2a.backend.c.PluginC");
        pluginClassNames.add ("gov.sandia.n2a.backend.neuroml.PluginNeuroML");
        for (String arg : args)
        {
            if (arg.startsWith ("plugin=")) pluginClassNames.add (arg.substring (7));
        }

        File[] pluginDirs = new File[1];
        pluginDirs[0] = new File (AppData.properties.get ("resourceDir"), "plugins");

        if (! PluginManager.initialize (new N2APlugin (), pluginClassNames.toArray (new String[0]), pluginDirs))
        {
            System.err.println (PluginManager.getInitializationErrors ());
        }

        // Create the main frame.
        EventQueue.invokeLater (new Runnable ()
        {
            public void run ()
            {
                LafManager.load ();
                new MainFrame ();
                setUncaughtExceptionHandler (MainFrame.instance);
            }
        });

        // Build labels for the About dialog as the last thing this thread does, effectively in the background.
        AboutDialog.initializeLabels ();
    }

    public static void setUncaughtExceptionHandler (final JFrame parent)
    {
        Thread.setDefaultUncaughtExceptionHandler (new UncaughtExceptionHandler ()
        {
            public void uncaughtException (Thread thread, final Throwable throwable)
            {
                File crashdump = new File (AppData.properties.get ("resourceDir"), "crashdump");
                try
                {
                    PrintStream err = new PrintStream (crashdump);
                    throwable.printStackTrace (err);
                    err.close ();
                }
                catch (FileNotFoundException e) {}

                JOptionPane.showMessageDialog
                (
                    parent,
                    "<html><body><p style='width:300px'>Our apologies, but an exception was raised.<br>"
                    + "<br>Details have been saved in " + crashdump.getAbsolutePath () + "<br>"
                    + "<br>Please consider filing a bug report on github, including the crashdump file and a description of what you were doing at the time.</p></body></html>",
                    "Internal Error",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}

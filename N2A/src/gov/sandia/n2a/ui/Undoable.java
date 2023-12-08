/*
Copyright 2016-2023 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui;

import java.awt.Component;

import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoableEdit;

public class Undoable implements UndoableEdit
{
    protected boolean   hasBeenDone = false;  // In the original AbstractUndoableEdit class, this was initialized true. We set it false and expect the caller to run redo() during creation of this object.
    protected boolean   alive       = true;
    public    Component tab         = MainFrame.instance == null ? null : MainFrame.instance.tabs.getSelectedComponent ();

    public void die ()
    {
        alive = false;
    }

    public void undo () throws CannotUndoException
    {
        if (! canUndo ()) throw new CannotUndoException ();
        hasBeenDone = false;
        if (tab != null) MainFrame.instance.tabs.setSelectedComponent (tab);
    }

    public boolean canUndo ()
    {
        return alive && hasBeenDone;
    }

    public void redo () throws CannotRedoException
    {
        if (! canRedo ()) throw new CannotRedoException();
        hasBeenDone = true;
        if (tab != null) MainFrame.instance.tabs.setSelectedComponent (tab);
    }

    public boolean canRedo ()
    {
        return alive && ! hasBeenDone;
    }

    public boolean addEdit (UndoableEdit edit)
    {
        return false;
    }

    public boolean replaceEdit (UndoableEdit edit)
    {
        return false;
    }

    /**
        Indicates that this edit has become null and thus should be removed.
        This can happen if it incorporates two edits that exactly neutralized each other, such as an add followed by a delete.
    **/
    public boolean anihilate ()
    {
        return false;
    }

    public boolean isSignificant ()
    {
        return true;
    }

    public String getPresentationName ()
    {
        return getClass ().getSimpleName ();
    }

    public String getUndoPresentationName ()
    {
        return "Undo " + getPresentationName ();
    }

    public String getRedoPresentationName ()
    {
        return "Redo " + getPresentationName ();
    }
}

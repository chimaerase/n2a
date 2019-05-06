/*
Copyright 2017-2019 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.util.List;

import javax.swing.tree.TreeNode;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.Undoable;
import gov.sandia.n2a.ui.eq.FilteredTreeModel;
import gov.sandia.n2a.ui.eq.PanelEquationGraph;
import gov.sandia.n2a.ui.eq.PanelEquationTree;
import gov.sandia.n2a.ui.eq.PanelEquations;
import gov.sandia.n2a.ui.eq.PanelModel;
import gov.sandia.n2a.ui.eq.PanelEquations.StoredView;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodePart;
import gov.sandia.n2a.ui.eq.tree.NodeVariable;

public class ChangeVariableToInherit extends Undoable
{
    protected StoredView   view = PanelModel.instance.panelEquations.new StoredView ();
    protected List<String> path;
    protected MNode  treeBefore;
    protected String valueAfter;

    public ChangeVariableToInherit (NodeVariable variable, String valueAfter)
    {
        NodeBase parent = (NodeBase) variable.getParent ();
        path = parent.getKeyPath ();
        this.valueAfter = valueAfter;
        treeBefore = new MVolatile (variable.source.key (), "");
        treeBefore.merge (variable.source.getSource ());  // Built from the top-level doc, not the collated tree.
    }

    public void undo ()
    {
        super.undo ();

        view.restore ();
        NodePart parent = (NodePart) NodeBase.locateNode (path);
        if (parent == null) throw new CannotUndoException ();
        NodePart grandparent = (NodePart) parent.getParent ();

        // Update the database
        MPart mparent = parent.source;
        mparent.clear ("$inherit");
        String nameBefore = treeBefore.key ();
        mparent.set (treeBefore, nameBefore);

        // Update the GUI

        PanelEquations pe = PanelModel.instance.panelEquations;
        PanelEquationTree pet = parent.getTree ();
        FilteredTreeModel model = (FilteredTreeModel) pet.tree.getModel ();
        PanelEquationGraph peg = pe.panelEquationGraph;

        parent.build ();
        if (grandparent == null) parent     .findConnections ();
        else                     grandparent.findConnections ();
        parent.filter (FilteredTreeModel.filterLevel);
        if (parent.visible (FilteredTreeModel.filterLevel)) model.nodeStructureChanged (parent);

        TreeNode[] nodePath = parent.child (nameBefore).getPath ();
        pet.updateOrder (nodePath);
        pet.updateVisibility (nodePath);
        if (grandparent != null  &&  grandparent == pe.part)
        {
            peg.reconnect ();
            peg.paintImmediately ();
        }
    }

    public void redo ()
    {
        super.redo ();

        view.restore ();
        NodePart parent = (NodePart) NodeBase.locateNode (path);
        if (parent == null) throw new CannotRedoException ();
        NodePart grandparent = (NodePart) parent.getParent ();

        // Update database
        MPart mparent = parent.source;
        mparent.clear (treeBefore.key ());
        mparent.set (valueAfter, "$inherit");

        // Update GUI

        PanelEquations pe = PanelModel.instance.panelEquations;
        PanelEquationTree pet = parent.getTree ();
        FilteredTreeModel model = (FilteredTreeModel) pet.tree.getModel ();
        PanelEquationGraph peg = pe.panelEquationGraph;

        parent.build ();
        if (grandparent == null) parent     .findConnections ();
        else                     grandparent.findConnections ();
        parent.filter (FilteredTreeModel.filterLevel);
        model.nodeStructureChanged (parent);

        TreeNode[] nodePath = parent.child ("$inherit").getPath ();
        pet.updateOrder (nodePath);
        pet.updateVisibility (nodePath);
        if (grandparent != null  &&  grandparent == pe.part)
        {
            peg.reconnect ();
            peg.paintImmediately ();
        }
    }
}

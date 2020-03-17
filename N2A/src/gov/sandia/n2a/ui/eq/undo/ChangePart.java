/*
Copyright 2017-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JTree;
import javax.swing.tree.TreeNode;
import javax.swing.undo.CannotRedoException;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.eqset.EquationEntry;
import gov.sandia.n2a.eqset.EquationSet;
import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.eqset.EquationSet.ConnectionBinding;
import gov.sandia.n2a.language.AccessVariable;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Visitor;
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

public class ChangePart extends Undoable
{
    protected StoredView   view = PanelModel.instance.panelEquations.new StoredView ();
    protected List<String> path;   // to the container of the part being renamed
    protected String       nameBefore;
    protected String       nameAfter;
    protected MNode        savedTree;  // The entire subtree from the top document. If not from top document, then at least a single node for the part itself.

    /**
        @param node The part being renamed.
    **/
    public ChangePart (NodePart node, String nameBefore, String nameAfter)
    {
        NodeBase parent = (NodeBase) node.getTrueParent ();
        path = parent.getKeyPath ();
        this.nameBefore = nameBefore;
        this.nameAfter  = nameAfter;

        savedTree = new MVolatile ();
        if (node.source.isFromTopDocument ()) savedTree.merge (node.source.getSource ());
    }

    public void undo ()
    {
        super.undo ();
        apply (nameAfter, nameBefore);
    }

    public void redo ()
    {
        super.redo ();
        apply (nameBefore, nameAfter);
    }

    public void apply (String nameBefore, String nameAfter)
    {
        int viewSize = view.path.size ();
        if (viewSize > path.size ())  // The name change applies to a graph node, which should be the focus.
        {
            view.path.set (viewSize - 1, nameBefore);
        }
        view.restore ();

        NodePart parent = (NodePart) NodeBase.locateNode (path);
        if (parent == null) throw new CannotRedoException ();
        NodeBase temp = parent.child (nameBefore);
        if (! (temp instanceof NodePart)) throw new CannotRedoException ();
        NodePart nodeBefore = (NodePart) temp;

        // Update the database
        
        //   Move the subtree
        MPart mparent = parent.source;
        mparent.clear (nameBefore);
        mparent.set (savedTree, nameAfter);
        MPart oldPart = (MPart) mparent.child (nameBefore);
        MPart newPart = (MPart) mparent.child (nameAfter);

        //   Change connection bindings.
        //   See ChangeVariable.apply() for a similar procedure. More detailed comments appear there.
        //   We make use of static functions in that class to do the heavy work of emitting code with name changes.
        //   TODO: This approach will probably fail on parts that contain references to themselves.
        PanelEquations pe = PanelModel.instance.panelEquations;
        List<List<String>> references = new ArrayList<List<String>> ();
        try
        {
            MPart doc = pe.root.source;
            EquationSet compiled = new EquationSet (doc);
            List<String> keypath = new ArrayList<String> (path.subList (1, path.size ()));
            EquationSet eold;
            EquationSet enew;
            if (oldPart == null)
            {
                EquationSet p = (EquationSet) compiled.getObject (keypath);
                eold = new EquationSet (p, nameBefore);
                p.parts.add (eold);
                keypath.add (nameAfter);
            }
            else
            {
                keypath.add (nameBefore);
                eold = (EquationSet) compiled.getObject (keypath);
                keypath.set (keypath.size () - 1, nameAfter);
            }
            enew = (EquationSet) compiled.getObject (keypath);

            try
            {
                compiled.resolveConnectionBindings ();
                compiled.resolveLHS ();
                compiled.resolveRHS ();
            }
            catch (Exception e) {}
            ChangeVariable.prepareConnections (compiled);

            // Collect variables that might have changed.
            List<Variable> users = collectVariables (compiled, eold);
            if (eold.dependentConnections != null)
            {
                // Each equation set tracks connection bindings which depend on it for their resolution.
                // The variable associated with such a connection binding could explicitly mention the part name.
                for (ConnectionBinding cb : eold.dependentConnections) users.add (cb.variable);
            }

            eold.name = enew.name;
            for (Variable v : users)
            {
                List<String> ref = v.getKeyPath ();
                MNode n = doc.child (ref.toArray ());
                String oldKey = n.key ();
                String newKey = ChangeVariable.changeReferences (eold, n, v);
                if (! newKey.equals (oldKey))  // Handle a change in variable name.
                {
                    NodeBase nb = pe.root.locateNodeFromHere (ref);
                    n.parent ().move (oldKey, newKey);
                    ref.set (ref.size () - 1, newKey);
                    nb.source = (MPart) doc.child (ref.toArray ());
                }
                if (v.container != enew  &&  v.container != eold) references.add (ref);  // Queue GUI updates for nodes other than the primary ones.
            }
        }
        catch (Exception e) {}

        // Update GUI

        boolean graphParent =  parent == pe.part;
        PanelEquationTree pet = graphParent ? null : parent.getTree ();
        FilteredTreeModel model = null;
        if (pet != null) model = (FilteredTreeModel) pet.tree.getModel ();
        PanelEquationGraph peg = pe.panelEquationGraph;  // Only used if graphParent is true.

        NodePart nodeAfter = (NodePart) parent.child (nameAfter);  // It's either a NodePart or it's null. Any other case should be blocked by GUI constraints.
        boolean addGraphNode = false;
        if (oldPart == null)  // Only one node will remain when we are done.
        {
            if (nodeAfter == null)  // This is a simple rename, with no restructuring. Keep nodeBefore.
            {
                nodeAfter = nodeBefore;
                nodeAfter.source = newPart;
                if (graphParent) peg.updatePart (nodeAfter);
            }
            else  // Use existing nodeAfter, so get rid of nodeBefore.
            {
                if (model == null) FilteredTreeModel.removeNodeFromParentStatic (nodeBefore);
                else               model.removeNodeFromParent (nodeBefore);
                if (graphParent) peg.removePart (nodeBefore);
            }
        }
        else  // Need two nodes
        {
            if (nodeAfter == null)  // Need a node to hold the new part.
            {
                int index = parent.getIndex (nodeBefore);
                nodeAfter = new NodePart (newPart);
                nodeAfter.hide = graphParent;
                if (model == null) FilteredTreeModel.insertNodeIntoUnfilteredStatic (nodeAfter, parent, index);
                else               model.insertNodeIntoUnfiltered (nodeAfter, parent, index);
                addGraphNode = true;
            }

            nodeBefore.build ();
            nodeBefore.findConnections ();
            nodeBefore.filter (FilteredTreeModel.filterLevel);
            if (nodeBefore.visible (FilteredTreeModel.filterLevel))
            {
                if (graphParent)  // Need to update entire model under fake root.
                {
                    PanelEquationTree subpet = nodeBefore.getTree ();
                    if (subpet != null)
                    {
                        FilteredTreeModel submodel = (FilteredTreeModel) subpet.tree.getModel ();
                        submodel.nodeStructureChanged (nodeBefore);
                        subpet.animate ();
                    }
                }
                else if (model != null)
                {
                    model.nodeStructureChanged (nodeBefore);
                }
            }
            else
            {
                parent.hide (nodeBefore, model);
            }
        }

        nodeAfter.build ();
        if (graphParent) parent   .findConnections ();
        else             nodeAfter.findConnections ();
        nodeAfter.filter (FilteredTreeModel.filterLevel);

        pe.resetBreadcrumbs ();
        TreeNode[] nodePath = nodeAfter.getPath ();
        Set<PanelEquationTree> needAnimate = new HashSet<PanelEquationTree> ();
        if (pet == null)
        {
            PanelEquationTree.updateOrder (null, nodePath);
            PanelEquationTree.updateVisibility (null, nodePath, -2, false);
        }
        else
        {
            pet.updateOrder (nodePath);
            pet.updateVisibility (nodePath);  // Will include nodeStructureChanged(), if necessary.
            needAnimate.add (pet);
        }

        for (List<String> ref : references)
        {
            NodeVariable n = (NodeVariable) pe.root.locateNodeFromHere (ref);
            if (n == null) continue;

            // Rebuild n, because equations and/or their conditions may have changed.
            n.build ();
            n.findConnections ();
            n.filter (FilteredTreeModel.filterLevel);
            if (n.visible (FilteredTreeModel.filterLevel))  // n's visibility won't change
            {
                PanelEquationTree subpet = n.getTree ();
                if (subpet == null) continue;
                JTree subtree = subpet.tree;
                FilteredTreeModel submodel = (FilteredTreeModel) subtree.getModel ();
                NodeBase subparent = (NodeBase) n.getParent ();

                submodel.nodeStructureChanged (n);  // Node will collapse if it was open. Don't worry about this.

                FontMetrics fm = n.getFontMetrics (subtree);
                n.updateColumnWidths (fm);
                subparent.updateTabStops (fm);
                subparent.allNodesChanged (submodel);
                needAnimate.add (subpet);
            }
        }

        for (PanelEquationTree ap : needAnimate) ap.animate ();

        if (graphParent)
        {
            if (addGraphNode)
            {
                peg.addPart (nodeAfter);  // builds tree
            }
            else
            {
                PanelEquationTree subpet = nodeAfter.getTree ();
                if (subpet != null)
                {
                    FilteredTreeModel submodel = (FilteredTreeModel) subpet.tree.getModel ();
                    submodel.nodeStructureChanged (nodeAfter);
                    subpet.animate ();
                }
            }
            nodeAfter.hide = false;
            nodeAfter.graph.takeFocusOnTitle ();
            peg.reconnect ();
            peg.repaint ();
        }
    }

    public List<Variable> collectVariables (EquationSet s, EquationSet renamed)
    {
        List<Variable> result = new ArrayList<Variable> ();
        for (EquationSet p : s.parts) result.addAll (collectVariables (p, renamed));

        // Regular variables might mention the part name, on either the LHS or RHS.
        class PartVisitor implements Visitor
        {
            boolean found;
            public boolean visit (Operator op)
            {
                if (op instanceof AccessVariable)
                {
                    AccessVariable av = (AccessVariable) op;
                    if (av.reference.resolution.contains (renamed)) found = true;
                    return false;
                }
                return true;
            }
        };
        PartVisitor visitor = new PartVisitor ();
        for (Variable v : s.variables)
        {
            visitor.found = v.reference.resolution.contains (renamed);
            for (EquationEntry ee : v.equations)
            {
                if (visitor.found) break;
                ee.expression.visit (visitor);
                if (ee.condition != null) ee.condition.visit (visitor);
            }
            if (visitor.found) result.add (v);
        }

        return result;
    }
}

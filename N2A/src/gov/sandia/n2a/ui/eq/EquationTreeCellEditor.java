/*
Copyright 2013-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.tree.TreeCellEditor;
import javax.swing.tree.TreePath;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import gov.sandia.n2a.language.UnitValue;
import gov.sandia.n2a.ui.MainFrame;
import gov.sandia.n2a.ui.MainTabbedPane;
import gov.sandia.n2a.ui.SafeTextTransferHandler;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodeEquation;
import gov.sandia.n2a.ui.eq.tree.NodePart;
import gov.sandia.n2a.ui.eq.tree.NodeVariable;

/**
    Custom tree cell editor that cooperates with NodeBase icon and text styles.
    Adds a few other nice behaviors:
    * Makes cell editing act more like a text document.
      - No visible border
      - Extends full width of tree panel
    * Selects the value portion of an equation, facilitating the user to make simple changes.
    * Instant one-click edit mode, rather than 1.2s delay.
**/
@SuppressWarnings("serial")
public class EquationTreeCellEditor extends AbstractCellEditor implements TreeCellEditor, TreeSelectionListener
{
    protected EquationTreeCellRenderer renderer;
    protected UndoManager              undoManager;
    protected JTextField               oneLineEditor;
    protected JTextArea                multiLineEditor;
    protected JScrollPane              multiLinePane;      // provides scrolling for multiLineEditor, and acts as the editingComponent
    protected JComboBox<String>        choiceEditor;
    protected JScrollBar               rangeEditor;
    protected JLabel                   iconHolder   = new JLabel ();
    protected List<JLabel>             labels       = new ArrayList<JLabel> ();

    protected JTree                    focusTree;
    protected TreePath                 lastPath;
    protected boolean                  multiLineRequested; // Indicates that the next getTreeCellEditorComponent() call should return multi-line, even if the node is normally single line.
    protected JTree                    editingTree;        // Could be different than focusTree
    protected NodeBase                 editingNode;
    protected boolean                  editingTitle;       // Indicates that we are in a graph node title rather than a proper tree.
    protected Container                editingContainer;
    protected Component                editingComponent;
    protected int                      offset;
    protected static int               offsetPerLevel;     // How much to indent per tree level to accommodate for expansion handles.
    protected String                   rangeUnits;
    protected double                   rangeLo;
    protected double                   rangeHi;
    protected double                   rangeStepSize;

    public EquationTreeCellEditor (EquationTreeCellRenderer renderer)
    {
        this.renderer = renderer;
        undoManager = new UndoManager ();
        editingContainer = new EditorContainer ();

        editingContainer.add (iconHolder);
        for (int i = 0; i < 2; i++)
        {
            JLabel l = new JLabel ();
            editingContainer.add (l);
            labels.add (l);
        }


        oneLineEditor = new JTextField ();
        oneLineEditor.setBorder (new EmptyBorder (0, 0, 0, 0));

        oneLineEditor.getDocument ().addUndoableEditListener (undoManager);
        InputMap inputMap = oneLineEditor.getInputMap ();
        inputMap.put (KeyStroke.getKeyStroke ("control Z"),       "Undo");
        inputMap.put (KeyStroke.getKeyStroke ("control Y"),       "Redo");
        inputMap.put (KeyStroke.getKeyStroke ("shift control Z"), "Redo");
        ActionMap actionMap = oneLineEditor.getActionMap ();
        actionMap.put ("Undo", new AbstractAction ("Undo")
        {
            public void actionPerformed (ActionEvent evt)
            {
                try {undoManager.undo ();}
                catch (CannotUndoException e) {}
            }
        });
        actionMap.put ("Redo", new AbstractAction ("Redo")
        {
            public void actionPerformed (ActionEvent evt)
            {
                try {undoManager.redo();}
                catch (CannotRedoException e) {}
            }
        });

        oneLineEditor.addFocusListener (new FocusListener ()
        {
            public void focusGained (FocusEvent e)
            {
                // Analyze text of control and set an appropriate selection
                String text = oneLineEditor.getText ();
                int equals = text.indexOf ('=');
                int at     = text.indexOf ('@');
                if (equals >= 0  &&  equals < text.length () - 1)  // also check for combiner character
                {
                    if (":+*/<>".indexOf (text.charAt (equals + 1)) >= 0) equals++;
                }
                if (at < 0)  // no condition
                {
                    if (equals >= 0)  // a single-line equation
                    {
                        oneLineEditor.setCaretPosition (text.length ());
                        oneLineEditor.moveCaretPosition (equals + 1);
                    }
                    else  // A part name
                    {
                        oneLineEditor.setCaretPosition (text.length ());
                    }
                }
                else if (equals > at)  // a multi-conditional line that has "=" in the condition
                {
                    oneLineEditor.setCaretPosition (0);
                    oneLineEditor.moveCaretPosition (at);
                }
                else  // a single-line equation with a condition
                {
                    oneLineEditor.setCaretPosition (equals + 1);
                    oneLineEditor.moveCaretPosition (at);
                }
            }

            public void focusLost (FocusEvent e)
            {
                if (editingNode != null)
                {
                    stopCellEditing ();
                    if (! editingTree.hasFocus ())
                    {
                        PanelEquationTree pet = (PanelEquationTree) editingTree.getParent ().getParent ();
                        pet.yieldFocus ();
                    }
                    ((MainTabbedPane) MainFrame.instance.tabs).setPreferredFocus (PanelModel.instance, editingTree);
                }
            }
        });

        oneLineEditor.addActionListener (new ActionListener ()
        {
            public void actionPerformed (ActionEvent e)
            {
                stopCellEditing ();
            }
        });

        MouseAdapter mouseListener = new MouseAdapter ()
        {
            public void mouseClicked (MouseEvent me)
            {
                if (me.getClickCount () == 2  &&  editingNode instanceof NodePart)
                {
                    // Drill down
                    NodePart part = (NodePart) editingNode;  // Save node, because stopCellEditing() will set it to null.
                    stopCellEditing ();
                    PanelModel.instance.panelEquations.drill (part);
                }
            }
        };
        oneLineEditor.addMouseListener (mouseListener);

        TransferHandler xfer = new SafeTextTransferHandler ()
        {
            public boolean importData (TransferSupport support)
            {
                if      (editingNode instanceof NodeVariable) setSafeTypes ("Equation", "Variable");
                else if (editingNode instanceof NodeEquation) setSafeTypes ("Equation");
                else                                          setSafeTypes ();
                boolean result = super.importData (support);
                if (! result) result = editingTree.getTransferHandler ().importData (support);
                return result;
            }
        };
        oneLineEditor.setTransferHandler (xfer);


        multiLineEditor = new JTextArea ();
        multiLinePane = new JScrollPane (multiLineEditor);
        multiLineEditor.setLineWrap (true);
        multiLineEditor.setWrapStyleWord (true);
        multiLineEditor.setRows (6);
        multiLineEditor.setTabSize (4);
        multiLineEditor.setTransferHandler (xfer);

        multiLineEditor.getDocument ().addUndoableEditListener (undoManager);
        inputMap = multiLineEditor.getInputMap ();
        inputMap.put (KeyStroke.getKeyStroke ("ENTER"),           "insert-break");
        inputMap.put (KeyStroke.getKeyStroke ("control ENTER"),   "none");
        inputMap.put (KeyStroke.getKeyStroke ("control Z"),       "Undo");
        inputMap.put (KeyStroke.getKeyStroke ("control Y"),       "Redo");
        inputMap.put (KeyStroke.getKeyStroke ("shift control Z"), "Redo");
        actionMap = multiLineEditor.getActionMap ();
        actionMap.put ("Undo", new AbstractAction ("Undo")
        {
            public void actionPerformed (ActionEvent evt)
            {
                try {undoManager.undo ();}
                catch (CannotUndoException e) {}
            }
        });
        actionMap.put ("Redo", new AbstractAction ("Redo")
        {
            public void actionPerformed (ActionEvent evt)
            {
                try {undoManager.redo();}
                catch (CannotRedoException e) {}
            }
        });

        FocusListener focusListener = new FocusListener ()
        {
            public void focusGained (FocusEvent e)
            {
            }

            public void focusLost (FocusEvent e)
            {
                if (editingNode != null) stopCellEditing ();
                if (! editingTree.hasFocus ())
                {
                    PanelEquationTree pet = (PanelEquationTree) editingTree.getParent ().getParent ();
                    pet.yieldFocus ();
                }
                ((MainTabbedPane) MainFrame.instance.tabs).setPreferredFocus (PanelModel.instance, editingTree);
            }
        };
        multiLineEditor.addFocusListener (focusListener);

        multiLineEditor.addKeyListener (new KeyAdapter ()
        {
            public void keyPressed (KeyEvent e)
            {
                if (e.getKeyCode () == KeyEvent.VK_ENTER  &&  e.isControlDown ()) stopCellEditing ();
            }
        });

        multiLineEditor.addMouseListener (mouseListener);


        choiceEditor = new JComboBox<String> ();
        choiceEditor.setUI (new BasicComboBoxUI ());  // Avoid borders on edit box, to save space.

        choiceEditor.addPopupMenuListener (new PopupMenuListener ()
        {
            public void popupMenuWillBecomeVisible (PopupMenuEvent e)
            {
            }

            public void popupMenuWillBecomeInvisible (PopupMenuEvent e)
            {
                // Have to test if we are still editing, because this may have been triggered by "finishEditing" action.
                if (editingNode != null) stopCellEditing ();
            }

            public void popupMenuCanceled (PopupMenuEvent e)
            {
            }
        });

        inputMap = choiceEditor.getInputMap ();
        inputMap.put (KeyStroke.getKeyStroke ("ENTER"),  "finishEditing");
        inputMap.put (KeyStroke.getKeyStroke ("ESCAPE"), "cancelEditing");
        actionMap = choiceEditor.getActionMap ();
        actionMap.put ("finishEditing", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                stopCellEditing ();
            }
        });
        actionMap.put ("cancelEditing", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                cancelCellEditing ();
            }
        });

        choiceEditor.addFocusListener (focusListener);


        rangeEditor = new JScrollBar (JScrollBar.HORIZONTAL);

        inputMap = rangeEditor.getInputMap ();
        inputMap.put (KeyStroke.getKeyStroke ("ENTER"),  "finishEditing");
        inputMap.put (KeyStroke.getKeyStroke ("ESCAPE"), "cancelEditing");
        actionMap = rangeEditor.getActionMap ();
        actionMap.put ("finishEditing", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                stopCellEditing ();
            }
        });
        actionMap.put ("cancelEditing", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                cancelCellEditing ();
            }
        });

        rangeEditor.addFocusListener (focusListener);
    }

    public static void staticUpdateUI ()
    {
        int left  = (Integer) UIManager.get ("Tree.leftChildIndent");
        int right = (Integer) UIManager.get ("Tree.rightChildIndent");
        offsetPerLevel = left + right;
    }

    public void updateUI ()
    {
        oneLineEditor  .updateUI ();
        multiLinePane  .updateUI ();
        multiLineEditor.updateUI ();
    }

    public Component getTitleEditorComponent (JTree tree, NodePart value, boolean open)
    {
        return getTreeCellEditorComponent (tree, value, open, false, true);
    }

    @Override
    public Component getTreeCellEditorComponent (JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row)
    {
        return getTreeCellEditorComponent (tree, value, expanded, leaf, false);
    }

    public Component getTreeCellEditorComponent (JTree tree, Object value, boolean expanded, boolean leaf, boolean isTitle)
    {
        editingTree     = tree;
        editingNode     = (NodeBase) value;
        editingTitle    = isTitle;
        offset          = renderer.getTextOffset ();
        Font fontBase   = tree.getFont ();
        Font fontPlain  = editingNode.getPlainFont (fontBase);
        Font fontStyled = editingNode.getStyledFont (fontBase);
        FontMetrics fm  = tree.getFontMetrics (fontStyled);

        iconHolder.setIcon (renderer.getIconFor (editingNode, expanded, leaf));

        String text;
        String param;
        // A variable is only visible in parameter mode if it is actually parameter, so no need to check for "param" in metadata. 
        if (editingNode instanceof NodeVariable  &&  FilteredTreeModel.filterLevel == FilteredTreeModel.PARAM)
        {
            // Add static labels for all columns except the value. See EquationTreeCellRenderer.getTreeCellRendererComponent()
            NodeBase      p            = editingNode.getTrueParent ();
            List<Integer> columnWidths = p.getMaxColumnWidths (editingNode.getColumnGroup (), fm);
            List<String>  columns      = editingNode.getColumns (true, expanded);  // NodeVariable should always return 3 columns.
            for (int i = 0; i < 2; i++)  // Set up the first two columns to display as fixed text in the editor.
            {
                JLabel l = labels.get (i);
                l.setText (columns.get (i));
                l.setFont (fontPlain);
                l.setVisible (true);
                l.setLocation (offset, 0);
                offset += columnWidths.get (i);
            }

            text = columns.get (2);  // 3rd column contains the value of the parameter.
            param = editingNode.source.get ("$metadata", "param");
        }
        else
        {
            for (int i = 0; i < 2; i++) labels.get (i).setVisible (false);
            text = editingNode.toString ();  // Fetch user object.
            param = "";
        }

        // Update editing component
        if (editingComponent != null) editingContainer.remove (editingComponent);
        if (param.contains (","))  // Dropdown list with fixed set of options.
        {
            editingComponent = choiceEditor;
            choiceEditor.removeAllItems ();
            String[] pieces = param.split (",");
            for (String c : pieces) choiceEditor.addItem (c);
            choiceEditor.setSelectedItem (text);
        }
        else if (param.startsWith ("["))  // Numeric range
        {
            editingComponent = rangeEditor;

            String[] pieces = param.substring (1).split ("]", 2);
            rangeUnits = "";
            if (pieces.length == 2) rangeUnits = pieces[1];
            pieces = pieces[0].split (":");
            rangeLo = Double.valueOf (pieces[0]);
            rangeHi = Double.valueOf (pieces[1]);
            rangeStepSize = 1;
            if (pieces.length == 3) rangeStepSize = Double.valueOf (pieces[2]);

            int steps = (int) Math.round ((rangeHi - rangeLo) / rangeStepSize);
            double current = new UnitValue (text).value;
            int c = (int) Math.round ((current - rangeLo) / rangeStepSize);
            c = Math.max (c, 0);
            c = Math.min (c, steps);
            rangeEditor.setValues (c, 1, 0, steps + 1);
        }
        else  // Plain text
        {
            int textWidth = fm.stringWidth (text);
            int treeWidth = tree.getWidth ();
            if (! isTitle  &&  (text.contains ("\n")  ||  textWidth > treeWidth  ||  multiLineRequested))
            {
                editingComponent = multiLinePane;
                multiLineEditor.setText (text);
                multiLineEditor.setFont (fontStyled);
                multiLineEditor.setEditable (! PanelModel.instance.panelEquations.locked);
                int equals = text.indexOf ('=');
                if (equals >= 0) multiLineEditor.setCaretPosition (equals);
                multiLineRequested = false;
            }
            else
            {
                editingComponent = oneLineEditor;
                oneLineEditor.setText (text);
                oneLineEditor.setFont (fontStyled);
                oneLineEditor.setEditable (! PanelModel.instance.panelEquations.locked);
            }
            undoManager.discardAllEdits ();
        }
        editingContainer.add (editingComponent);

        return editingContainer;
    }

    @Override
    public Object getCellEditorValue ()
    {
        String value = "";
        if      (editingComponent == choiceEditor)  value = choiceEditor.getSelectedItem ().toString ();
        else if (editingComponent == oneLineEditor) value = oneLineEditor.getText ();
        else if (editingComponent == multiLinePane) value = multiLineEditor.getText ();
        else                      // rangeEditor
        {
            double c = rangeEditor.getValue () * rangeStepSize + rangeLo;
            if (isInteger (rangeLo)  &&  isInteger (rangeHi)  &&  isInteger (rangeStepSize))
            {
                value = String.valueOf ((int) Math.round (c));
            }
            else
            {
                value = String.valueOf (c);
            }
            value += rangeUnits;
        }
        if (labels.get (0).isVisible ())  // parameter mode, so add back name and assignment character
        {
            value = labels.get (0).getText ().trim () + "=" + value;
        }
        return value;
    }

    public static boolean isInteger (double value)
    {
        // Could compute threshold using Math.ulp(1.0), which gives machine epsilon, but that is a bit too tight.
        // The number here is arbitrary, but reasonable for double. It is roughly sqrt(epsilon).
        return Math.abs (value - Math.round (value)) < 1e-8;  
    }

    /**
        Indicate whether the current cell may be edited.
        Apparently, this method is called in only two situations:
        1) The left or right (but not center or scroll wheel) mouse button has been clicked.
           In this case, event is the MouseEvent. For a double-click, the first and second presses
           are delivered as separate events (with click count set to 2 on the second press).
        2) startEditingAtPath() was called, and JTree wants to verify that editing is permitted.
           In this case, event is null. 
    **/
    @Override
    public boolean isCellEditable (EventObject event)
    {
        if (event == null)  // Just verify that editing is permitted.
        {
            if (lastPath == null) return false;
            Object o = lastPath.getLastPathComponent ();
            if (! (o instanceof NodeBase)) return false;
            NodeBase node = (NodeBase) o;
            if (! node.allowEdit ()) return false;
            return true;
        }
        else if (event instanceof MouseEvent)
        {
            MouseEvent me = (MouseEvent) event;
            int x = me.getX ();
            int y = me.getY ();
            int clicks = me.getClickCount ();
            if (SwingUtilities.isLeftMouseButton (me))
            {
                if (clicks == 1)
                {
                    if (focusTree == null) return false;
                    final TreePath path = focusTree.getPathForLocation (x, y);
                    if (path != null  &&  path.equals (lastPath))  // Second click on node, but not double-click.
                    {
                        // Prevent second click from initiating edit if this is the icon of the root node.
                        // Similar to the logic in PanelEquatonTree tree mouse listener
                        NodeBase node = (NodeBase) path.getLastPathComponent ();
                        NodePart root = (NodePart) focusTree.getModel ().getRoot ();
                        if (node == root)
                        {
                            boolean expanded = focusTree.isExpanded (path);
                            int iconWidth = root.getIcon (expanded).getIconWidth ();  // expanded doesn't really matter to icon width, as NodePart uses only one icon.
                            if (x < iconWidth) return false;
                        }

                        // Initiate edit
                        EventQueue.invokeLater (new Runnable ()
                        {
                            public void run ()
                            {
                                focusTree.startEditingAtPath (path);
                            }
                        });
                    }
                }
            }
        }
        // Always return false from this method. Instead, initiate editing indirectly.
        return false;
    }

    public boolean stopCellEditing ()
    {
        NodeBase node = editingNode;
        editingNode = null;

        fireEditingStopped ();
        editingTree.setEditable (! PanelModel.instance.panelEquations.locked);  // Restore lock that may have been unset to allow user to view truncated fields.
        node.applyEdit (editingTree);

        return true;
    }

    public void cancelCellEditing ()
    {
        NodeBase node = editingNode;
        editingNode = null;

        fireEditingCanceled ();
        editingTree.setEditable (! PanelModel.instance.panelEquations.locked);

        // We only get back an empty string if we explicitly set it before editing starts.
        // Certain types of nodes do this when inserting a new instance into the tree, via NodeBase.add()
        // We desire in this case that escape cause the new node to evaporate.
        Object o = node.getUserObject ();
        if (! (o instanceof String)) return;
        if (((String) o).isEmpty ()) node.delete (editingTree, true);
    }

    public void valueChanged (TreeSelectionEvent e)
    {
        if (! e.isAddedPath ()) return;
        focusTree = (JTree) e.getSource ();
        lastPath = focusTree.getSelectionPath ();
    }

    /**
        Draws the node icon.
    **/
    public class EditorContainer extends Container
    {
        public EditorContainer ()
        {
            setLayout (null);
        }

        /**
            Place editingComponent past the icon
        **/
        public void doLayout ()
        {
            int w = getWidth ();
            int h = getHeight ();

            Dimension d = iconHolder.getPreferredSize ();
            int y = Math.max (0, h - d.height) / 2;
            iconHolder.setBounds (0, y, d.width, d.height);

            if (labels.get (0).isVisible ())
            {
                for (int i = 0; i < 2; i++)
                {
                    JLabel l = labels.get (i);
                    d = l.getPreferredSize ();
                    int x = l.getX ();
                    y = Math.max (0, h - d.height) / 2;
                    l.setBounds (x, y, d.width, d.height);
                }
            }

            if (editingComponent != null)
            {
                // Most editors will be sized to exactly fill available area.
                // In the case of a combo-box (choiceEditor), there is no way to scroll interior content.
                // Also, it looks terrible if stretched wide. Better to show at its preferred size.
                d = editingComponent.getPreferredSize ();
                if (editingComponent != choiceEditor) d.width = w - offset; 
                y = Math.max (0, h - d.height) / 2;
                editingComponent.setBounds (offset, y, d.width, d.height);
            }
        }

        public Dimension getPreferredSize ()
        {
            JViewport vp          = (JViewport) editingTree.getParent ();
            Dimension extent      = vp.getExtentSize ();
            Point     p           = vp.getViewPosition ();
            int       rightMargin = p.x + extent.width;

            Dimension pSize = editingComponent.getPreferredSize ();
            Insets insets = editingTree.getInsets ();
            pSize.width = rightMargin - offsetPerLevel * editingNode.getLevel () - insets.left - insets.right;
            pSize.width = Math.max (100, pSize.width);

            Dimension rSize = renderer.getPreferredSize ();
            pSize.height = Math.max (pSize.height, rSize.height);
            // Renderer has exactly the same icon, so no need to do separate check for icon size.

            return pSize;
        }
    }
}

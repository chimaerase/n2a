/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.eqset;

import gov.sandia.n2a.parsing.functions.AdditionFunction;
import gov.sandia.n2a.parsing.functions.DivisionFunction;
import gov.sandia.n2a.parsing.functions.EvaluationContext;
import gov.sandia.n2a.parsing.functions.EvaluationException;
import gov.sandia.n2a.parsing.functions.MultiplicationFunction;
import gov.sandia.n2a.parsing.functions.SubtractionFunction;
import gov.sandia.n2a.parsing.gen.ASTConstant;
import gov.sandia.n2a.parsing.gen.ASTFunNode;
import gov.sandia.n2a.parsing.gen.ASTListNode;
import gov.sandia.n2a.parsing.gen.ASTNodeBase;
import gov.sandia.n2a.parsing.gen.ASTNodeRenderer;
import gov.sandia.n2a.parsing.gen.ASTNodeTransformer;
import gov.sandia.n2a.parsing.gen.ASTOpNode;
import gov.sandia.n2a.parsing.gen.ASTRenderingContext;
import gov.sandia.n2a.parsing.gen.ASTTransformationContext;
import gov.sandia.n2a.parsing.gen.ASTVarNode;
import gov.sandia.n2a.parsing.gen.ExpressionParser;
import gov.sandia.n2a.parsing.gen.ParseException;
import gov.sandia.umf.platform.connect.orientdb.ui.NDoc;
import gov.sandia.umf.platform.ui.ensemble.domains.Parameter;
import gov.sandia.umf.platform.ui.ensemble.domains.ParameterDomain;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import java.lang.Number;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.swing.ImageIcon;

public class EquationSet implements Comparable<EquationSet>
{
    public NDoc                              source;
    public String                            name;
    public EquationSet                       container;
    public NavigableSet<Variable>            variables;
    public NavigableSet<EquationSet>         parts;
    public NavigableMap<String, EquationSet> connectionBindings;  // non-null iff this is a connection
    public boolean                           connected;
    public Map<String, String>               metadata;            // TODO: better to refer metadata requests to source object (the Part). Part should implement a getNamedValue() function that refers requests up the inheritance chain.
    public List<Variable>                    ordered;
    public List<ArrayList<EquationSet>>      splits;              // Enumeration of the $type splits this part can go through

    public EquationSet (String name)
    {
        this.name = name;
    }

    public EquationSet (NDoc part) throws Exception
    {
        this ("", null, part);
    }

    public EquationSet (String name, EquationSet container, NDoc source) throws Exception
    {
        EquationSet c = container;
        while (c != null)
        {
            if (c.source == source)  // TODO: should this be based on Id's instead of object references?
            {
                throw new Exception ("Self-referential loop in part: " + source);
            }
            c = c.container;
        }

        this.name      = name;
        this.container = container;
        this.source    = source;
        variables      = new TreeSet<Variable> ();
        parts          = new TreeSet<EquationSet> ();
        metadata       = new HashMap<String, String> ();

        // TODO: Includes, Bridges and Layers should all be stored the same way in the DB.
        // They should all refer to a Part, and they should all have an alias.
        // "Bridges" have additional attributes to name the aliases of the parts they connect.

        // Includes
        List<NDoc> associations = source.getValid ("associations", new ArrayList<NDoc> (), List.class);
        for (NDoc a : associations)
        {
            if (((String) a.get ("type")).equalsIgnoreCase ("include"))
            {
                String aname = a.get ("name");  // TODO: default alias name should be assigned at creation time, not filled in here!
                if (aname == null)
                {
                    throw new Exception ("Need to set include name in DB");
                }
                NDoc dest = a.get ("dest");
                parts.add (new EquationSet (aname, this, dest));
            }
        }

        // Layers
        List<NDoc> layers = source.getValid ("layers", new ArrayList<NDoc> (), List.class);
        for (NDoc l : layers)
        {
            parts.add (new EquationSet ((String) l.get ("name"), this, (NDoc) l.get ("derivedPart")));
        }

        // Bridges
        List<NDoc> bridges = source.getValid ("bridges", new ArrayList<NDoc> (), List.class);
        for (NDoc b : bridges)
        {
            NDoc connection = b.get ("derivedPart");
            EquationSet s = new EquationSet ((String) b.get ("name"), this, connection);
            parts.add (s);

            // Configure the connection bindings in s (the "bridge" equation set)

            //   Collect "connect" associations from connection part. These indicate the alias and type
            //   of parts that get connected. We will match the types of parts that this "bridge"
            //   connects in order to infer which alias refers to each one.
            //   TODO: This approach is fragile in multiple ways, and should be changed ASAP.
            List<NDoc> connectionAssociations = new ArrayList<NDoc> ();
            NDoc parent = connection.get ("parent");
            associations = parent.getValid ("associations", new ArrayList<NDoc> (), List.class);
            for (NDoc a : associations)
            {
                if (((String) a.get ("type")).equalsIgnoreCase ("connect"))
                {
                    connectionAssociations.add (a);
                }
            }

            //   Scan the list of parts connected by this "bridge"
            List<NDoc> connected = b.getValid ("layers", new ArrayList<NDoc> (), List.class);
            for (NDoc l : connected)
            {
                // Retrieve the equation set associated with the connected part
                String lname = l.get ("name");
                EquationSet e = parts.floor (new EquationSet (lname));
                if (e == null  ||  ! e.name.equals (lname))
                {
                    // We should NEVER get here, since a "bridge" directly references layers which should already be added. 
                    throw new Exception ("Connection references a Part that does not exist.");
                }

                // Determine the alias by scanning associations of the connection part
                NDoc layerType = ((NDoc) l.get ("derivedPart")).get ("parent");
                for (NDoc a : connectionAssociations)
                {
                    NDoc connectionType = a.get ("dest");
                    if (layerType.getId ().equals (connectionType.getId ()))
                    {
                        // Stored the binding
                        if (s.connectionBindings == null)
                        {
                            s.connectionBindings = new TreeMap<String, EquationSet> ();
                        }
                        String aname = a.get ("name");
                        if (! s.connectionBindings.containsKey (aname))
                        {
                            s.connectionBindings.put (aname, e);
                        }
                    }
                }

                e.connected = true;
            }
        }

        // Local equations
        List<NDoc> eqs = source.getValid ("eqs", new ArrayList<NDoc> (), List.class);
        for (NDoc e : eqs)
        {
            EquationEntry ee = new EquationEntry (e);
            Variable v = variables.floor (ee.variable);
            if (v == null  ||  ! v.equals (ee.variable))
            {
                add (ee.variable);
            }
            else
            {
                v.replace (ee);
            }
        }
        //   Model output equations (in the old system) are also effectively local equations.
        //   However, they could be anonymous, so we must generate names for them.
        eqs = source.getValid ("outputEqs", new ArrayList<NDoc> (), List.class);
        int outputNumber = 0;
        for (NDoc e : eqs)
        {
            EquationEntry ee = new EquationEntry (e);
            if (ee.variable.name.length () == 0)  // naked expression
            {
                // convert into a tracer for the referenced variable
                ee.variable.addAttribute ("output");
                ee.variable.name = "output" + outputNumber;
            }
            outputNumber++;
            Variable v = variables.floor (ee.variable);
            if (v == null  ||  ! v.equals (ee.variable))
            {
                add (ee.variable);
            }
            else
            {
                v.replace (ee);
            }
        }

        // Metadata
        Map<String, String> namedValues = source.getValid ("$metadata", new TreeMap<String, String> (), Map.class);
        metadata.putAll (namedValues);

        // Inherits
        NDoc parent = source.get ("parent");  // TODO: should be any number of parents (multiple-inheritance)
        if (parent != null)
        {
            merge (new EquationSet ("", this, parent));
        }

        pushDown ();
    }

    public boolean add (Variable v)
    {
        v.container = this;
        return variables.add (v);
    }

    public void replace (Variable v)
    {
        variables.remove (v);
        variables.add (v);
        v.container = this;
    }

    /**
        Merge given equation set into this, where contents of this always take precedence.
    **/
    public void merge (EquationSet s)
    {
        // Merge variables, and collate equations within each variable
        for (Variable v : s.variables)
        {
            Variable r = variables.floor (v);
            if (r == null  ||  ! r.equals (v))
            {
                add (v);
            }
            else
            {
                r.merge (v);
            }
        }

        // Merge parts, and collate contents of any that are already present
        for (EquationSet p : s.parts)
        {
            EquationSet r = parts.floor (p);
            if (r == null  ||  ! r.equals (p))
            {
                parts.add (p);
                p.container = this;
            }
            else
            {
                r.merge (p);
            }
        }

        // Merge connection bindings
        // In theory, connection bindings are only created by the level above a given part, so none of the following should be necessary.
        // TODO: connectionBindings must maintain object identity after parts are merged!!!
        if (connectionBindings == null)
        {
            connectionBindings = s.connectionBindings;
        }
        else if (s.connectionBindings != null)
        {
            s.connectionBindings.putAll (connectionBindings);  // putAll() replaces entries in the receiving map, so we must merge into s first to preserve precedence
            connectionBindings = s.connectionBindings;
        }

        // Merge metadata
        s.metadata.putAll (metadata);
        metadata = s.metadata;
    }

    /**
        Move any equation that refers to a sub-namespace down into the associated equation list.
    **/
    public void pushDown ()
    {
        Set<Variable> temp = new TreeSet<Variable> (variables);
        variables.clear ();
        for (Variable v : temp)
        {
            pushDown (v);
        }
    }

    /**
        Place the given Variable in an appropriate sub-part, unless it has a $up or no dot operator.
    **/
    public void pushDown (Variable v)
    {
        int index = v.name.indexOf (".");
        if (index < 0  ||  v.name.startsWith ("$up."))
        {
            // Store at the current level.
            replace (v);
        }
        else
        {
            final String prefix = v.name.substring (0, index);
            EquationSet p = parts.floor (new EquationSet (prefix));
            if (p == null  ||  ! p.name.equals (prefix))
            {
                replace (v);
            }
            else
            {
                class Defixer implements ASTNodeTransformer
                {
                    public ASTNodeBase transform (ASTNodeBase node)
                    {
                        String result = node.toString ();
                        if (result.startsWith (prefix + "."))
                        {
                            node.setValue (result.substring (prefix.length () + 1));
                        }
                        return node;
                    }
                }

                ASTTransformationContext context = new ASTTransformationContext ();
                context.add (ASTVarNode.class, new Defixer ());

                v.transform (context);
                if (v.name.startsWith (prefix + "."))
                {
                    v.name = v.name.substring (prefix.length () + 1);
                }
                p.pushDown (v);
            }
        }
    }

    /**
        Compute the fully-qualified name of this equation set.
    **/
    public String prefix ()
    {
        if (container == null)
        {
            return name;
        }
        String temp = container.prefix ();
        if (temp.length () > 0)
        {
            return temp + "." + name;
        }
        return name;
    }

    /**
        Determine which equation set actually contains the value of the given variable.
        This applies all the name-resolution rules in the N2A language. In particular:
        <ul>
        <li>All name-resolution is referred up the part hierarchy until a match is found, or we run out of hierarchy.
        <li>$up skips one level.
        <li>A prefix that references the endpoint of a connection will be referred to that part.
        </ul>
        @param v Variable.name will be modified until it matches the name in the resolved EquationSet.
        Variable.reference must already exist.
        Any EquationSets visited after this one will be appended to Variable.reference.resolution.
    **/
    public EquationSet resolveEquationSet (Variable v)
    {
        // Check $variables
        if (v.name.startsWith ("$"))
        {
            if (v.name.startsWith ("$up."))
            {
                v.name = v.name.substring (4);
                v.reference.resolution.add (container);
                return container.resolveEquationSet (v);
            }
            else
            {
                return this;  // Other $variables are always treated as local, even if they are undefined. For example: you would never want to inherit $n from a container!
            }
        }

        // Check namespace references. These take precedence over variable names.
        String[] ns = v.name.split ("\\.", 2);
        if (ns.length > 1)
        {
            if (connectionBindings != null)
            {
                EquationSet alias = connectionBindings.get (ns[0]);
                if (alias != null)
                {
                    v.name = ns[1];
                    v.reference.resolution.add (connectionBindings.floorEntry (ns[0]));
                    return alias.resolveEquationSet (v);
                }
            }
            EquationSet down = parts.floor (new EquationSet (ns[0]));
            if (down != null  &&  down.name.equals (ns[0]))
            {
                v.name = ns[1];
                v.reference.resolution.add (down);
                return down.resolveEquationSet (v);
            }
        }

        // Check variable names
        if (variables.contains (v))
        {
            return this;  // found it!
        }

        // Check if this is a direct reference to a part.
        // Similar to a "down" reference, except no variable is involved.
        EquationSet part = parts.floor (new EquationSet (v.name));
        if (part != null  &&  part.name.equals (v.name))
        {
            v.name = "(part)";  // parentheses are illegal in a variable name, so this should clearly identify a direct part reference
            return this;
        }

        // Look up the containment hierarchy
        if (container == null)
        {
            return null;  // unresolved!!
        }
        v.reference.resolution.add (container);
        return container.resolveEquationSet (v);
    }

    /**
        Search for the given variable within this specific equation set. If not found, return null.
    **/
    public Variable find (Variable v)
    {
        Variable result = variables.floor (v);
        if (result != null  &&  result.compareTo (v) == 0)
        {
            return result;
        }
        return null;
    }

    /**
        Fill in the Variable.reference field of the given variable with the actual location of the data.
        Typically, the location is the same as the variable itself, unless it is a proxy for a variable in
        another equation set.
    **/
    public void resolveLHS ()
    {
        for (EquationSet s : parts)
        {
            s.resolveLHS ();
        }

        for (Variable v : variables)
        {
            Variable query = new Variable (v.name, v.order);
            query.reference = new VariableReference ();
            EquationSet dest = resolveEquationSet (query);
            if (dest != null)
            {
                query.reference.variable = dest.find (query);
            }
            v.reference = query.reference;
            if (v.reference.variable != v  &&  v.reference.variable != null)
            {
                v.addAttribute ("reference");
                v.reference.variable.addAttribute ("externalWrite");
                v.reference.variable.addDependency (v);  // v.reference.variable receives an external write from v, and therefore depends on it
                for (EquationEntry e : v.reference.variable.equations)  // because the equations of v.reference.variable must share its storage with us, they must respect unknown ordering and not simply write the value
                {
                    e.assignment = "+=";
                }
            }
        }
    }

    /**
        Attach the appropriate Variable to each ASTVarNode.
    **/
    public void resolveRHS ()
    {
        for (EquationSet s : parts)
        {
            s.resolveRHS ();
        }
    
        class Resolver implements ASTNodeTransformer
        {
            public Variable from;
            public ASTNodeBase transform (ASTNodeBase node)
            {
                ASTVarNode vn = (ASTVarNode) node;
                Variable query = new Variable (vn.getVariableName (), vn.getOrder ());
                query.reference = new VariableReference ();
                EquationSet dest = resolveEquationSet (query);
                if (dest != null)
                {
                    query.reference.variable = dest.find (query);
                    if (query.reference.variable == null)
                    {
                        if (query.name.equals ("(part)"))
                        {
                            // Create a placeholder for the part.
                            // It will not be entered into the part's collection of variables.
                            query.reference.variable = new Variable (query.name, 0);
                            query.reference.variable.container = dest;
                        }
                    }
                    else
                    {
                        from.addDependency (query.reference.variable);
                        if (from.container != query.reference.variable.container)
                        {
                            query.reference.variable.addAttribute ("externalRead");
                        }
                    }
                }
                vn.reference = query.reference;
                return vn;
            }
        }
        Resolver resolver = new Resolver ();
        ASTTransformationContext context = new ASTTransformationContext ();
        context.add (ASTVarNode.class, resolver);
    
        for (Variable v : variables)
        {
            resolver.from = v;
            v.transform (context);
        }
    }

    public String flatList (boolean showNamespace)
    {
        StringBuilder result = new StringBuilder ();

        ASTRenderingContext context;
        if (showNamespace)
        {
            class Prefixer implements ASTNodeRenderer
            {
                public String render (ASTNodeBase node, ASTRenderingContext context)
                {
                    ASTVarNode vn = (ASTVarNode) node;
                    String result = vn.toString ();
                    if (vn.reference == null  ||  vn.reference.variable == null)
                    {
                        return "<unresolved!>" + result;
                    }
                    else
                    {
                        return "<" + vn.reference.variable.container.prefix () + ">" + result;
                    }
                }
            }

            context = new ASTRenderingContext (true);
            context.add (ASTVarNode.class, new Prefixer ());
        }
        else
        {
            context = new ASTRenderingContext (true);
        }

        String prefix = prefix ();
        if (connectionBindings != null)
        {
            for (Entry<String, EquationSet> e : connectionBindings.entrySet ())
            {
                result.append (prefix + "." + e.getKey () + " = ");
                EquationSet s = e.getValue ();
                if (showNamespace)
                {
                    result.append ("<");
                    if (s.container != null)
                    {
                        result.append (s.container.prefix ());
                    }
                    result.append (">");
                }
                result.append (s.name + "\n");
            }
        }
        for (Variable v : variables)
        {
            for (EquationEntry e : v.equations)
            {
                result.append (prefix + "." + e.render (context) + "\n");
            }
        }

        for (EquationSet e : parts)
        {
            result.append (e.flatList (showNamespace));
        }

        return result.toString ();
    }

    /**
        Convert this equation list into an equivalent object where every included
        part with $n==1 is merged into its containing part.  Append (+=) equations
        are joined together into one long equation.
        flatten() is kind of like a dual of pushDown().
    **/
    public void flatten ()
    {
        TreeSet<EquationSet> temp = new TreeSet<EquationSet> (parts);
        for (final EquationSet s : temp)
        {
            s.flatten ();

            // Check if connection. They must remain a separate equation set for code-generation purposes.
            if (s.connectionBindings != null)
            {
                continue;
            }
            if (s.connected)
            {
                continue;
            }

            // Check if $n==1
            Variable n = s.find (new Variable ("$n", 0));
            if (n != null)  // We only do more work if $n exists. Non-existent $n is the same as $n==1
            {
                // make sure no other orders of $n exist
                Variable n2 = s.variables.higher (n);
                if (n2.name.equals ("$n"))
                {
                    continue;
                }
                // check contents of $n
                if (n.equations.size () != 1)
                {
                    continue;
                }
                EquationEntry ne = n.equations.first ();
                if (! ne.assignment.equals ("="))
                {
                    continue;
                }
                // If we can't evaluate $n as a number, then we treat it as 1
                // Otherwise, we check the actual value.
                if (ne.expression != null)
                {
                    Object value = ne.expression.eval ();
                    if (value instanceof Number  &&  ((Number) value).floatValue () != 1)
                    {
                        continue;
                    }
                }
                s.variables.remove (n);  // We don't want $n in the merged set.
            }

            // Don't merge if there are any conflicting $variables.
            boolean conflict = false;
            for (Variable v : s.variables)
            {
                if (! v.name.startsWith ("$")  ||  v.name.startsWith ("$up"))
                {
                    continue;
                }
                Variable d = find (v);
                if (d != null  &&  d.name.equals (v.name))  // for this match we don't care about order; that is, any differential order on either side causes a conflict
                {
                    conflict = true;
                    break;
                }
            }
            if (conflict)
            {
                continue;
            }

            // Merge

            final String prefix = s.name;
            parts.remove (s);

            //   Variables
            final TreeSet<String> names = new TreeSet<String> ();
            for (Variable v : s.variables)
            {
                names.add (v.name);
            }

            class Prefixer implements ASTNodeTransformer
            {
                public ASTNodeBase transform (ASTNodeBase node)
                {
                    String result = node.toString ();
                    if (result.startsWith ("$"))
                    {
                        if (result.startsWith ("$up."))
                        {
                            node.setValue (result.substring (4));
                        }
                        // otherwise, don't modify references to $variables
                    }
                    else if (names.contains (result))
                    {
                        node.setValue (prefix + "." + result);
                    }
                    return node;
                }
            }

            ASTTransformationContext context = new ASTTransformationContext ();
            context.add (ASTVarNode.class, new Prefixer ());

            for (Variable v : s.variables)
            {
                if (v.name.startsWith ("$"))
                {
                    if (v.name.startsWith ("$up."))
                    {
                        v.name = v.name.substring (4);
                    }
                    // otherwise merge all $variables with containing set
                }
                else
                {
                    v.name = prefix + "." + v.name;
                }
                v.transform (context);
                Variable v2 = find (v);
                if (v2 == null)
                {
                    add (v);
                }
                else
                {
                    v2.mergeExpressions (v);
                }
            }

            //   Parts
            for (EquationSet sp : s.parts)
            {
                sp.name = prefix + "." + sp.name;
                parts.add (sp);
            }

            //   Metadata
            for (Entry<String, String> e : s.metadata.entrySet ())
            {
                metadata.put (prefix + "." + e.getKey (), e.getValue ());
            }
        }
    }

    /**
        Assembles that list of all variables that can be used in an output expression.
        Depends on results of: resolveLHS() (optional, enables us to remove "reference" variables)
    **/
    public ParameterDomain getOutputParameters ()
    {
        ImageIcon icon;
        if (connectionBindings == null)
        {
            icon = ImageUtil.getImage ("layer.gif");
        }
        else
        {
            icon = ImageUtil.getImage ("bridge.gif");
        }
        ParameterDomain result = new ParameterDomain (name, icon);  // TODO: should we return the empty string, or replace it with something?

        for (Variable v : variables)
        {
            if (! v.hasAttribute ("reference"))
            {
                result.addParameter (new Parameter (v.nameString (), ""));
            }
        }
        for (EquationSet s : parts)
        {
            result.addSubdomain (s.getOutputParameters ());
        }

        return result;
    }

    /**
        Add variables to equation set that are needed, but that the user should not normally define.
        Depends on results of: none
    **/
    public void addSpecials ()
    {
        for (EquationSet s : parts)
        {
            s.addSpecials ();
        }

        setInit (false);  // force $init to exist

        Variable v = new Variable ("$dt", 0);
        if (add (v))
        {
            v.equations = new TreeSet<EquationEntry> ();  // simpler to make an empty equation set than to test for null all the time
        }

        v = new Variable ("$t", 0);
        if (add (v))
        {
            v.equations = new TreeSet<EquationEntry> ();
        }

        v = new Variable ("$type", 0);
        if (add (v))
        {
            v.equations = new TreeSet<EquationEntry> ();
        }

        if (connectionBindings == null)  // Compartment
        {
            v = new Variable ("$index", 0);
            if (add (v))
            {
                v.equations = new TreeSet<EquationEntry> ();
            }
        }
    }

    /**
        Remove any variables (particularly $variables) that are not referenced by some
        equation. These values do not input to any other calculation, and they are not
        displayed. Therefore they are a waste of time and space.
        Depends on results of: resolveRHS(), fillIntegratedVariables()
    **/
    public void removeUnused ()
    {
        for (EquationSet s : parts)
        {
            s.removeUnused ();
        }

        TreeSet<Variable> temp = new TreeSet<Variable> (variables);
        for (Variable v : temp)
        {
            if (v.hasUsers)
            {
                continue;
            }
            if (v.name.startsWith ("$")  &&  v.equations.size () > 0)  // even if a $variable has no direct users, we must respect any statements about it
            {
                continue;
            }
            if (v.hasAttribute ("output"))  // Outputs must always exist!
            {
                continue;
            }
            variables.remove (v);
            // In theory, removing variables may reduce the dependencies on some other variable to 0.
            // Then we could remove that variable as well. This would require multiple passes or some
            // other scheme to recheck everything affected. We don't really need that much accounting
            // because most unused variables will be $variables we added preemptively.
        }
    }

    /**
        Add a variable for the lower-order integrated form of each derivative, if it does not already exist.
        Depends on results of: none
    **/
    public void fillIntegratedVariables ()
    {
        for (EquationSet s : parts)
        {
            s.fillIntegratedVariables ();
        }

        Set<Variable> temp = new TreeSet<Variable> (variables);
        for (Variable v : temp)
        {
            // Detect "reference" variables.
            // This code duplicates a lot of logic in resolveEquationSet(). It is useful to do this here
            // because resolveLHS() is overkill. 
            if (v.name.startsWith ("$up."))
            {
                continue;
            }
            if (connectionBindings != null)
            {
                String[] ns = v.name.split ("\\.", 2);
                if (ns.length > 1)
                {
                    if (connectionBindings.get (ns[0]) != null)
                    {
                        continue;
                    }
                }
            }

            // This may seem inefficient, but in general there will only be one order to process.
            Variable last = v;
            for (int o = v.order - 1; o >= 0; o--)
            {
                Variable vo = new Variable (v.name, o);
                Variable found = find (vo);
                if (found == null)
                {
                    add (vo);
                    vo.equations = new TreeSet<EquationEntry> ();
                    found = vo;
                }
                found.addDependency (last);
                last = found;
            }
        }
    }

    /**
        Add "@ $init" to any $variable that lacks conditionals.
        This forces them to be evaluated only during the init phase.
        Depends on results of: none  (possibly addSpecials(), but not in current form)
    **/
    public void addInit () throws ParseException
    {
        for (EquationSet s : parts)
        {
            s.addInit ();
        }

        for (Variable v : variables)
        {
            // Skip all non-$variables. Also skip $init and $up
            if (v.name.startsWith ("$"))
            {
                if (v.name.startsWith ("$up."))  // A variable prefixed by $up is not a true $variable. Only $up by itself is.
                {
                    continue;
                }
                if (v.name.equals ("$init"))  // $init should have no conditionals whatsoever!
                {
                    continue;
                }
            }
            else
            {
                continue;
            }

            boolean hasInit = false;
            for (EquationEntry e : v.equations)
            {
                if (e.ifString.equals ("$init"))
                {
                    hasInit = true;
                    break;
                }
            }
            if (hasInit)
            {
                continue;
            }

            // Find an entry with no conditional
            EquationEntry e = v.equations.floor (new EquationEntry (v, ""));
            if (e != null  &&  e.ifString.equals (""))
            {
                e.ifString = "$init";
                e.conditional = ExpressionParser.parse (e.ifString);
            }
        }
    }

    /**
        Change the value of the constant $init in the current equation set.
        Used to indicate if we are in the init phase or not.
    **/
    public void setInit (boolean value)
    {
        Variable init = find (new Variable ("$init"));
        if (init == null)
        {
            EquationEntry e = new EquationEntry ("$init", 0);
            e.variable.addAttribute ("constant");
            e.expression = new ASTConstant (new Float (value ? 1.0 : 0.0));
            add (e.variable);
        }
        else
        {
            EquationEntry e = init.equations.first ();
            ASTConstant c = (ASTConstant) e.expression;
            c.setValue (new Float (value ? 1.0 : 0.0));
        }
    }

    public boolean getInit ()
    {
        Variable init = find (new Variable ("$init"));
        if (init == null) return false;
        EquationEntry e = init.equations.first ();
        ASTConstant c = (ASTConstant) e.expression;
        Object o = c.getValue ();
        if (! (o instanceof Float)) return false;
        return ((Float) o).floatValue () == 1.0;
    }

    public static ArrayList<EquationSet> getSplitFrom (ASTNodeBase node) throws Exception
    {
        ArrayList<EquationSet> result = new ArrayList<EquationSet> ();

        if (! (node instanceof ASTListNode))
        {
            throw new Exception ("$type expects a list of part names");
        }
        ASTListNode list = (ASTListNode) node;
        int count = list.getCount ();
        for (int i = 0; i < count; i++)
        {
            ASTNodeBase c = list.getChild (i);
            if (! (c instanceof ASTVarNode))
            {
                throw new Exception ("$type may only be assigned the name of a part");
            }
            ASTVarNode v = (ASTVarNode) c;
            if (v.reference == null  ||  v.reference.variable == null)
            {
                throw new Exception ("$type assigned fom an unresolved part name");
            }
            result.add (v.reference.variable.container);
        }

        return result;
    }

    /**
        Scans all conditional forms of $type, and stores the patterns in the splits field.
        Depends on results of: resolveLHS(), resolveRHS()
    **/
    public void collectSplits () throws Exception
    {
        for (EquationSet s : parts)
        {
            s.collectSplits ();
        }

        if (splits == null)
        {
            splits = new ArrayList<ArrayList<EquationSet>> ();
        }
        for (Variable v : variables)
        {
            if (v.reference == null  ||  v.reference.variable == null  ||  ! v.reference.variable.name.equals ("$type"))
            {
                continue;
            }
            EquationSet container = v.reference.variable.container;
            if (container.splits == null)  // in case we are referencing $type in another equation set
            {
                container.splits = new ArrayList<ArrayList<EquationSet>> ();
            }
            for (EquationEntry e : v.equations)
            {
                ArrayList<EquationSet> split = getSplitFrom (e.expression);
                if (! container.splits.contains (split))
                {
                    container.splits.add (split);
                }
            }
        }
    }

    /**
        Convenience function to assemble splits into (from,to) pairs for type conversion.
        Depends on results of: collectSplits()
    **/
    public Set<ArrayList<EquationSet>> getConversions ()
    {
        Set<ArrayList<EquationSet>> result = new TreeSet<ArrayList<EquationSet>> ();
        for (EquationSet p : parts)
        {
            for (ArrayList<EquationSet> split : p.splits)
            {
                for (EquationSet s : split)
                {
                    ArrayList<EquationSet> pair = new ArrayList<EquationSet> ();
                    pair.add (p);
                    pair.add (s);
                    result.add (pair);
                }
            }
        }
        return result;
    }

    public void addAttribute (String attribute, int connection, boolean withOrder, String[] names)
    {
        addAttribute (attribute, connection, withOrder, new TreeSet<String> (Arrays.asList (names)));
    }

    /*
     * @param attribute The string to add to the tags associated with each given variable.
     * @param connection Tri-state: 1 = must be a connection, -1 = must be a compartment, 0 = can be either one
     * @param withOrder Restricts name matching to exactly the same order of derivative,
     * that is, how many "prime" marks are appended to the variable name.
     * When false, matches any variable with the same base name.
     * @param names A set of variable names to search for and tag.
     */
    public void addAttribute (String attribute, int connection, boolean withOrder, Set<String> names)
    {
        for (EquationSet s : parts)
        {
            s.addAttribute (attribute, connection, withOrder, names);
        }

        if (connectionBindings == null)
        {
            if (connection == 1)
            {
                return;
            }
        }
        else
        {
            if (connection == -1)
            {
                return;
            }
        }
        for (Variable v : variables)
        {
            String name = v.name;
            if (withOrder)
            {
                name = v.nameString ();
            }
            if (names.contains (name))
            {
                v.addAttribute (attribute);
            }
        }
    }

    public void removeAttribute (String attribute, int connection, boolean withOrder, String[] names)
    {
        removeAttribute (attribute, connection, withOrder, new TreeSet<String> (Arrays.asList (names)));
    }

    public void removeAttribute (String attribute, int connection, boolean withOrder, Set<String> names)
    {
        for (EquationSet s : parts)
        {
            s.removeAttribute (attribute, connection, withOrder, names);
        }

        if (connectionBindings == null)
        {
            if (connection == 1)
            {
                return;
            }
        }
        else
        {
            if (connection == -1)
            {
                return;
            }
        }
        for (Variable v : variables)
        {
            String name = v.name;
            if (withOrder)
            {
                name = v.nameString ();
            }
            if (names.contains (name))
            {
                v.removeAttribute (attribute);
            }
        }
    }

    /**
        Identifies variables that have a known value before code generation.
        Also removes arithmetic operations that have no effect:
        <ul>
        <li>evaluate (node) == constant --> constant node
        <li>node + 0 --> node
        <li>node - 0 --> node
        <li>node * 1 --> node
        <li>node * 0 --> 0 (constant node)
        <li>node / 1 --> node
        </ul>
        Depends on results of: resolveRHS()  (so that named constants can be found during evaluation)
    **/
    public void findConstants ()
    {
        for (EquationSet s : parts)
        {
            s.findConstants ();
        }

        // TODO: create a new EvaluationContext class that works directly with Variables
        // TODO: use a single ec for entire EquationSet tree, not just current one
        // Perhaps create the ec and transform context in a higher function outside the recursion
        final EvaluationContext ec = new EvaluationContext ();
        class CollapseConstants implements ASTNodeTransformer
        {
            public ASTNodeBase transform (ASTNodeBase node)
            {
                try
                {
                    Object o = node.eval (ec);
                    if (o != null)
                    {
                        return new ASTConstant (o);
                    }
                }
                catch (EvaluationException exception)
                {
                }
                if (! (node instanceof ASTOpNode))
                {
                    return node;
                }

                // Otherwise try arithmetic simplifications
                if (node.getValue () instanceof AdditionFunction)
                {
                    Object c = node.getChild (0).getValue ();
                    if (c instanceof Number  &&  ((Number) c).doubleValue () == 0)
                    {
                        return node.getChild (1);
                    }
                    c = node.getChild (1).getValue ();
                    if (c instanceof Number  &&  ((Number) c).doubleValue () == 0)
                    {
                        return node.getChild (0);
                    }
                }
                if (node.getValue () instanceof SubtractionFunction)
                {
                    Object c = node.getChild (0).getValue ();
                    if (c instanceof Number  &&  ((Number) c).doubleValue () == 0)
                    {
                        return node.getChild (1);
                    }
                    c = node.getChild (1).getValue ();
                    if (c instanceof Number  &&  ((Number) c).doubleValue () == 0)
                    {
                        return node.getChild (0);
                    }
                }
                if (node.getValue () instanceof DivisionFunction)
                {
                    Object c = node.getChild (1).getValue ();
                    if (c instanceof Number  &&  ((Number) c).doubleValue () == 1)
                    {
                        return node.getChild (0);
                    }
                }
                if (node.getValue () instanceof MultiplicationFunction)
                {
                    Object c = node.getChild (0).getValue ();
                    if (c instanceof Number)
                    {
                        double value = ((Number) c).doubleValue ();
                        if (value == 0)
                        {
                            return new ASTConstant (new Double (0));
                        }
                        if (value == 1)
                        {
                            return node.getChild (1);
                        }
                    }
                    c = node.getChild (1).getValue ();
                    if (c instanceof Number)
                    {
                        double value = ((Number) c).doubleValue ();
                        if (value == 0)
                        {
                            return new ASTConstant (new Double (0));
                        }
                        if (value == 1)
                        {
                            return node.getChild (0);
                        }
                    }
                }

                return node;
            }
        }

        ASTTransformationContext context = new ASTTransformationContext ();
        CollapseConstants c = new CollapseConstants ();
        context.add (ASTOpNode  .class, c);
        context.add (ASTFunNode .class, c);

        for (Variable v : variables)
        {
            v.transform (context);

            // Check if we have a constant
            if (v.equations.size () != 1)
            {
                continue;
            }
            EquationEntry e = v.equations.first ();
            if (e.conditional != null)
            {
                continue;
            }
            if (e.expression.getClass () == ASTConstant.class)
            {
                v.addAttribute ("constant");
            }
        }
    }

    /**
        Identifies variables that act as subexpressions, and thus are not stored in the part's state.
        Depends on results of: none
    **/
    public void findTemporary () throws Exception
    {
        for (EquationSet s : parts)
        {
            s.findTemporary ();
        }

        for (Variable v : variables)
        {
            if (v.equations.size () == 0) continue;
            EquationEntry f = v.equations.first ();
            boolean hasTemporary = f.assignment != null  &&  f.assignment.equals (":=");
            for (EquationEntry e : v.equations)
            {
                boolean foundTemporary = e.assignment != null  &&  e.assignment.equals (":=");  
                if (foundTemporary != hasTemporary)
                {
                    throw new Exception ("A sub-expression has some conditional forms which don't use ':=' : " + v.container.prefix () + "." + v.name);
                }
                if (hasTemporary)
                {
                    e.assignment = "=";  // replace := with = for use in code generation
                }
            }
            if (hasTemporary)
            {
                v.addAttribute ("temporary");
            }
        }
    }

    /**
        Populates the order field with the sequence of variable evaluations that minimizes
        the need for buffering. If there are no cyclic dependencies, then this problem can
        be solved exactly. If there are cycles, then this method uses a simple heuristic:
        prioritize variables with the largest number of dependencies.
        Depends on results of: resolveRHS(), findTemporary()
    **/
    public void determineOrder ()
    {
        for (EquationSet s : parts)
        {
            s.determineOrder ();
        }

        // Reset variables for analysis
        ordered = new ArrayList<Variable> ();
        for (Variable v : variables)
        {
            v.before   = new ArrayList<Variable> ();
            v.priority = 0;
        }

        // Determine order constraints for each variable separately
        for (Variable v : variables)
        {
            v.setBefore ();
        }

        // Assign depth in dependency tree, processing variables with the most ordering constraints first
        class CompareDependency implements Comparator<Variable>
        {
            public int compare (Variable a, Variable b)
            {
                return b.before.size () - a.before.size ();
            }
        }
        PriorityQueue<Variable> queueDependency = new PriorityQueue<Variable> (variables.size (), new CompareDependency ());
        queueDependency.addAll (variables);
        for (Variable v = queueDependency.poll (); v != null; v = queueDependency.poll ())
        {
            v.visited = null;
            v.setPriority (1);
        }

        // Assemble dependency tree into flat list
        class ComparePriority implements Comparator<Variable>
        {
            public int compare (Variable a, Variable b)
            {
                return a.priority - b.priority;
            }
        }
        PriorityQueue<Variable> queuePriority = new PriorityQueue<Variable> (variables.size (), new ComparePriority ());
        queuePriority.addAll (variables);
        for (Variable v = queuePriority.poll (); v != null; v = queuePriority.poll ())
        {
            ordered.add (v);
        }

        // Tag any circular dependencies
        int count = ordered.size ();
        for (int index = 0; index < count; index++)
        {
            Variable v = ordered.get (index);
            if (v.uses == null)
            {
                continue;
            }
            for (Variable u : v.uses)
            {
                if (   u.container == this  // must be in same equation set for order to matter
                    && ! (u.name.equals (v.name)  &&  u.order == v.order + 1)  // must not be my derivative
                    && ! u.hasAttribute ("temporary")  // temporaries follow the opposite rule on ordering, so don't consider them here
                    &&  ordered.indexOf (u) < index)  // and finally, is it actually ahead of me in the odering?
                {
                    System.out.println ("cyclic dependency: " + v.name + " comes after " + u.name);
                    u.addAttribute ("cycle");  // must be buffered; otherwise we will get the "after" value rather than "before"
                }
            }
        }
    }

    /**
        Identifies variables that are integrated from their derivative.
        Depends on results of: resolveLHS()  (to identify references)
    **/
    public void findIntegrated ()
    {
        for (EquationSet s : parts)
        {
            s.findIntegrated ();
        }

        for (Variable v : variables)
        {
            if (v.hasAttribute ("reference"))
            {
                continue;
            }
            // Check if there is another equation that is exactly one order higher than us.
            // If so, then we must be integrated from it.
            if (find (new Variable (v.name, v.order + 1)) != null)
            {
                v.addAttribute ("integrated");
            }
        }
    }

    /**
        Identifies variables that have differential order higher than 0.
        Also tags any temporary variables that a derivative depends on.
        Depends on results of:
            fillIntegratedVariables() to get lower-order derivatives
            resolveRHS() to establish dependencies
            findTeporary() to identify temporaries
            All of these are optional.
    **/
    public void findDerivative ()
    {
        for (EquationSet s : parts)
        {
            s.findDerivative ();
        }

        for (Variable v : variables)
        {
            if (v.order > 0)
            {
                v.visitTemporaries ();  // sets attribute "derivativeOrDependency"
            }
        }
    }

    public String getNamedValue (String name)
    {
        return getNamedValue (name, "");
    }

    public String getNamedValue (String name, String defaultValue)
    {
        if (metadata.containsKey (name)) return metadata.get (name);
        return defaultValue;
    }

    public void setNamedValue (String name, String value)
    {
        metadata.put (name, value);
    }

    public int compareTo (EquationSet that)
    {
        return name.compareTo (that.name);
    }

    @Override
    public boolean equals (Object that)
    {
        if (this == that)
        {
            return true;
        }
        EquationSet s = (EquationSet) that;
        if (s == null)
        {
            return false;
        }
        return compareTo (s) == 0;
    }
}

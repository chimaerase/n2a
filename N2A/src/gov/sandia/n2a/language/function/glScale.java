/*
Copyright 2023 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language.function;

import gov.sandia.n2a.language.EvaluationException;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Type;
import gov.sandia.n2a.language.type.Instance;
import gov.sandia.n2a.language.type.Matrix;
import gov.sandia.n2a.language.type.Scalar;
import gov.sandia.n2a.linear.MatrixDense;

public class glScale extends Function
{
    public static Factory factory ()
    {
        return new Factory ()
        {
            public String name ()
            {
                return "glScale";
            }

            public Operator createInstance ()
            {
                return new glScale ();
            }
        };
    }

    public Type getType ()
    {
        return new Scalar ();
    }

    public Type eval (Instance context) throws EvaluationException
    {
        Matrix scale = (MatrixDense) operands[0].eval (context);

        MatrixDense result = new MatrixDense (4, 4);
        result.set (0, 0, scale.get (0));
        result.set (1, 1, scale.get (1));
        result.set (2, 2, scale.get (2));
        result.set (3, 3, 1);

        return result;
    }

    public String toString ()
    {
        return "glScale";
    }
}

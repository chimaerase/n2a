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
import gov.sandia.n2a.language.type.Scalar;
import gov.sandia.n2a.linear.MatrixDense;

public class glFrustum extends Function
{
    public static Factory factory ()
    {
        return new Factory ()
        {
            public String name ()
            {
                return "glFrustum";
            }

            public Operator createInstance ()
            {
                return new glFrustum ();
            }
        };
    }

    public Type getType ()
    {
        return new Scalar ();
    }

    public Type eval (Instance context) throws EvaluationException
    {
        double l = ((Scalar) operands[0].eval (context)).value;
        double r = ((Scalar) operands[1].eval (context)).value;
        double b = ((Scalar) operands[2].eval (context)).value;
        double t = ((Scalar) operands[3].eval (context)).value;
        double n = ((Scalar) operands[4].eval (context)).value;
        double f = ((Scalar) operands[5].eval (context)).value;

        MatrixDense result = new MatrixDense (4, 4);
        result.set (0, 0,  2 * n / (r - l));
        result.set (1, 1,  2 * n / (t - b));
        result.set (0, 2,  (r + l) / (r - l));
        result.set (1, 2,  (t + b) / (t - b));
        result.set (2, 2, -(f + n) / (f - n));
        result.set (3, 2, -1);
        result.set (2, 3, -2 * f * n / (f - n));

        return result;
    }

    public String toString ()
    {
        return "glFrustum";
    }
}

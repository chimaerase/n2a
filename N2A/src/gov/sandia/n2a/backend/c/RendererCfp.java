/*
Copyright 2018 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.c;

import java.util.HashSet;

import gov.sandia.n2a.eqset.EquationSet;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.OperatorArithmetic;
import gov.sandia.n2a.language.OperatorBinary;
import gov.sandia.n2a.language.OperatorLogical;
import gov.sandia.n2a.language.function.Event;
import gov.sandia.n2a.language.function.Exp;
import gov.sandia.n2a.language.function.Floor;
import gov.sandia.n2a.language.function.Gaussian;
import gov.sandia.n2a.language.function.Input;
import gov.sandia.n2a.language.function.Log;
import gov.sandia.n2a.language.function.Norm;
import gov.sandia.n2a.language.function.Output;
import gov.sandia.n2a.language.function.ReadMatrix;
import gov.sandia.n2a.language.function.Round;
import gov.sandia.n2a.language.function.Signum;
import gov.sandia.n2a.language.function.SquareRoot;
import gov.sandia.n2a.language.function.Tangent;
import gov.sandia.n2a.language.function.Uniform;
import gov.sandia.n2a.language.operator.Add;
import gov.sandia.n2a.language.operator.Divide;
import gov.sandia.n2a.language.operator.LT;
import gov.sandia.n2a.language.operator.Modulo;
import gov.sandia.n2a.language.operator.Multiply;
import gov.sandia.n2a.language.operator.MultiplyElementwise;
import gov.sandia.n2a.language.operator.NOT;
import gov.sandia.n2a.language.operator.Power;
import gov.sandia.n2a.language.type.Matrix;

public class RendererCfp extends RendererC
{
    protected Operator latch;

    protected static HashSet<Class<? extends Operator>> operatorsWithExponent = new HashSet<Class<? extends Operator>> ();
    static
    {
        operatorsWithExponent.add (Exp       .class);
        operatorsWithExponent.add (Gaussian  .class);
        operatorsWithExponent.add (Input     .class);
        operatorsWithExponent.add (Log       .class);
        operatorsWithExponent.add (Norm      .class);
        operatorsWithExponent.add (Output    .class);
        operatorsWithExponent.add (Power     .class);
        operatorsWithExponent.add (ReadMatrix.class);
        operatorsWithExponent.add (SquareRoot.class);
        operatorsWithExponent.add (Tangent   .class);
        operatorsWithExponent.add (Uniform   .class);
    }

    public RendererCfp (JobC job, StringBuilder result, EquationSet part)
    {
        super (job, result, part);
        useExponent = true;  // Turn on emission of fixed-point parameters in superclass.
    }

    /**
        Sometimes it is necessary to to let the superclass (RendererC) decide whether to
        render the operator or defer to the operator's own render method, but then continue
        with our work to render more code around the result. The two available methods are:
            op.render (this);          // Produces infinite loop.
            return super.render (op);  // We can't continue.
        This function sets up a latch to suppress recursion for one cycle.
    **/
    public void escalate (Operator op)
    {
        latch = op;
        op.render (this);
    }

    public boolean render (Operator op)
    {
        if (op == latch)
        {
            latch = null;
            return super.render (op);
        }

        // Operators that are explicitly forwarded to RendererC, because they simply add
        // one or more exponent parameters to the end of the usual function call.
        if (operatorsWithExponent.contains (op.getClass ())) return super.render (op);

        // Arithmetic operators
        // These are ordered to trap specific cases before more general ones.
        if (op instanceof OperatorLogical)
        {
            // Don't convert boolean to fixed-point except at the outermost expression. 
            if (op.parent != null  &&  op.parent instanceof OperatorLogical) return super.render (op);

            result.append ("(");
            escalate (op);
            result.append (" ? ");
            if (op instanceof NOT)
            {
                result.append ("0 : " + print (1, op.exponentNext) + ")");
            }
            else
            {
                result.append (print (1, op.exponentNext) + " : 0)");
            }
            return true;
        }
        if (op instanceof Multiply  ||  op instanceof MultiplyElementwise)
        {
            OperatorBinary b = (OperatorBinary) op;

            // Explanation of shift -- The exponent of the result will be the sum of the exponents
            // of the two operands. That new exponent will be associated with bit position 2*MSB.
            // We want the exponent at bit position MSB.
            int exponentRaw = b.operand0.exponentNext + b.operand1.exponentNext - Operator.MSB;  // Exponent at MSB position after a direct integer multiply.
            int shift = exponentRaw - b.exponentNext;

            if (shift == 0)
            {
                escalate (b);
            }
            else
            {
                if (b.getType () instanceof Matrix)
                {
                    if (b instanceof Multiply  ||  b.operand0.isScalar ()  ||  b.operand1.isScalar ())
                    {
                        result.append ("multiply (");
                    }
                    else  // MultiplyElementwise and both operands are matrices
                    {
                        result.append ("multiplyElementwise (");
                    }
                    b.operand0.render (this);
                    result.append (", ");
                    b.operand1.render (this);
                    result.append (", " + shift + ")");
                }
                else
                {
                    result.append ("(int32_t) ((int64_t) ");
                    escalate (b);
                    result.append (printShift (shift));
                    result.append (")");
                }
            }
            return true;
        }
        if (op instanceof Divide)
        {
            Divide d = (Divide) op;

            // Explanation of shift -- In a division, the quotient is effectively down-shifted by
            // the number of bits in the denominator, and its exponent is the difference between
            // the exponents of the numerator and denominator.
            int exponentRaw = d.operand0.exponentNext - d.operand1.exponentNext + Operator.MSB;  // Exponent in MSB from a direct integer division.
            int shift = exponentRaw - d.exponentNext;

            if (shift == 0)
            {
                escalate (d);
            }
            else
            {
                if (d.getType () instanceof Matrix)
                {
                    // See integer case below for how divide() will distribute shift between pre- and post-division.
                    result.append ("divide (");
                    d.operand0.render (this);
                    result.append (", ");
                    d.operand1.render (this);
                    result.append (", " + shift + ")");
                }
                else
                {
                    if (shift > 0)
                    {
                        result.append ("(int32_t) (((int64_t) ");
                        // OperatorBinary.render() will add parentheses around operand0 if it has lower
                        // precedence than division. This includes the case where it has lower precedence
                        // than shift, so we are safe.
                        d.render (this, " << " + shift + ") / ");
                        result.append (")");
                    }
                    else
                    {
                        result.append ("(");
                        escalate (d);
                        result.append (" >> " + -shift + ")");
                    }
                }
            }
            return true;
        }
        if (op instanceof Modulo)
        {
            Modulo m = (Modulo) op;

            if (m.operand0.exponentNext == m.operand1.exponentNext)
            {
                int shift = m.operand0.exponentNext - m.exponentNext;
                if (shift == 0) return super.render (m);
                result.append ("(");
                escalate (m);
                result.append (printShift (shift));
                result.append (")");
            }
            else
            {
                result.append ("mod (");
                m.operand0.render (this);
                result.append (", ");
                m.operand1.render (this);
                result.append (", " + m.operand0.exponentNext + ", " + m.operand1.exponentNext + ", " + m.exponentNext + ")");
            }
            return true;
        }
        if (op instanceof OperatorArithmetic)  // Add, Subtract, Negate, Transpose
        {
            int shift = op.exponent - op.exponentNext;  // Assume our operands all match our exponent.
            if (shift == 0) return super.render (op);
            if (op.getType () instanceof Matrix)
            {
                result.append ("(");
                escalate (op);
                result.append (").shift (" + shift + ")");
            }
            else
            {
                result.append ("(");

                boolean needParens = op.precedence () > new Add ().precedence ();  // In C++, shift is just below addition in precedence.
                if (needParens) result.append ("(");
                escalate (op);
                if (needParens) result.append (")");

                result.append (printShift (shift));
                result.append (")");
            }
            return true;
        }

        // Functions
        // These are listed in alphabetical order, with a catch-all at the end.
        if (op instanceof Event)
        {
            if (op.parent instanceof OperatorLogical) return super.render (op);

            Event e = (Event) op;
            int exponentRaw = Operator.MSB - e.eventType.valueIndex;
            int shift = exponentRaw - e.exponentNext;

            if (shift != 0) result.append ("(");
            result.append ("(flags & (" + bed.localFlagType + ") 0x1 << " + e.eventType.valueIndex + ")");
            if (shift != 0)
            {
                result.append (printShift (shift));
                result.append (")");
            }
            return true;
        }
        if (op instanceof Floor)
        {
            Floor f = (Floor) op;
            // Floor always sets operand[0].exponentNext to be same as f.exponentNext, so no shift is necessary.
            if (f.exponentNext >= Operator.MSB)
            {
                f.operands[0].render (this);
            }
            else if (f.exponentNext < 0)
            {
                result.append ("0");
            }
            else
            {
                result.append ("floor (");
                f.operands[0].render (this);
                int decimalMask = 0x7FFFFFFF >> 31 - (Operator.MSB - f.exponentNext);
                result.append (", " + decimalMask + ")");
            }
            return true;
        }
        if (op instanceof Round)
        {
            Round r = (Round) op;
            Operator a = r.operands[0];
            int shift = a.exponentNext - r.exponentNext;
            int decimalPlaces = Math.max (0, Operator.MSB - a.exponentNext);
            int mask = 0xFFFFFFFF << decimalPlaces;
            int half = 0;
            if (decimalPlaces > 0) half = 0x1 << decimalPlaces - 1;

            if (shift != 0) result.append ("(");
            result.append ("("); // Bitwise operators are low precedence, so we parenthesize them regardless.
            boolean needParens = a.precedence () > new Add ().precedence ();
            if (needParens) result.append ("(");
            a.render (this);
            if (needParens) result.append (")");
            result.append (" + " + half + " & " + mask);
            result.append (")"); // Close parens around bitwise operator
            if (shift != 0) result.append (printShift (shift) + ")");
            return true;
        }
        if (op instanceof Signum)
        {
            Signum s = (Signum) op;
            Operator a = s.operands[0];
            int one = 0x1 << Operator.MSB - s.exponentNext;
            boolean needParens = a.precedence () >= new LT ().precedence ();
            if (needParens) result.append ("(");
            a.render (this);
            if (needParens) result.append (")");
            result.append (" < 0 ? " + -one + ":" + one + ")");
            return true;
        }
        if (op instanceof Function)  // AbsoluteValue, Cosine, Grid, Max, Min, Sine
        {
            int shift = op.exponent - op.exponentNext;
            if (shift == 0) return super.render (op);

            if (op.getType () instanceof Matrix)
            {
                escalate (op);
                result.append (".shift (" + shift + ")");
            }
            else
            {
                result.append ("(");
                escalate (op);
                result.append (printShift (shift));
                result.append (")");
            }
            return true;
        }

        return super.render (op);
    }

    public String print (double d, int exponent)
    {
        if (d == 0) return "0";
        if (Double.isNaN (d)) return String.valueOf (Integer.MIN_VALUE);
        if (Double.isInfinite (d))
        {
            if (d < 0) return String.valueOf (Integer.MIN_VALUE + 1);
            return            String.valueOf (Integer.MAX_VALUE);
        }

        long bits = Double.doubleToLongBits (d);
        int  e    = Math.getExponent (d);
        bits |=   0x10000000000000l;  // set implied msb of mantissa (bit 52) to 1
        bits &= 0x801FFFFFFFFFFFFFl;  // clear exponent bits
        bits >>= 52 - Operator.MSB + exponent - e;
        return Integer.toString ((int) bits);
    }
}

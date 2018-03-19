#include "runtime.h"

// Rather than build separate object files and link into an archive, we simply merge all
// the code into a monolithic source and compile in one pass. This simplifies the process.

// Template implementations first, before any "using namespace" statements
#include "fl/Matrix.tcc"
#include "fl/MatrixFixed.tcc"
#include "fl/Vector.tcc"

// Regular sources, which are allowed to have "using namespace" statements
#ifdef N2A_SPINNAKER
# include "nosys.cc"
#endif
#include "io.cc"
#include "String.cc"   // TODO: will inlining all String methods make the object file bigger or smaller?
#include "Neighbor.cc"

template class MatrixAbstract<float>;
template class Matrix<float>;
template class MatrixFixed<float,3,1>;


// General functions ---------------------------------------------------------

float
uniform ()
{
    return (float) rand () / RAND_MAX;
}

float
uniform (float sigma)
{
    return sigma * rand () / RAND_MAX;
}

MatrixResult<float>
uniform (const MatrixAbstract<float> & sigma)
{
    int rows = sigma.rows ();
    int cols = sigma.columns ();
    if (cols == 1)
    {
        Vector<float> * result = new Vector<float> (rows);
        for (int i = 0; i < rows; i++) (*result)[i] = uniform (sigma(i,0));
        return result;
    }
    else if (rows == 1)
    {
        Vector<float> * result = new Vector<float> (cols);
        for (int i = 0; i < cols; i++) (*result)[i] = uniform (sigma(0,i));
        return result;
    }
    else
    {
        Vector<float> temp (cols);
        for (int i = 0; i < cols; i++) temp[i] = uniform ();
        return sigma * temp;
    }
}

// Box-Muller method (polar variant) for Gaussian random numbers.
static bool haveNextGaussian = false;
static float nextGaussian;
float
gaussian ()
{
    if (haveNextGaussian)
    {
        haveNextGaussian = false;
        return nextGaussian;
    }
    else
    {
        float v1, v2, s;
        do
        {
            v1 = uniform () * 2 - 1;   // between -1.0 and 1.0
            v2 = uniform () * 2 - 1;
            s = v1 * v1 + v2 * v2;
        }
        while (s >= 1 || s == 0);
        float multiplier = sqrt (- 2 * log (s) / s);
        nextGaussian = v2 * multiplier;
        haveNextGaussian = true;
        return v1 * multiplier;
    }
}

float
gaussian (float sigma)
{
    return sigma * gaussian ();
}

MatrixResult<float>
gaussian (const MatrixAbstract<float> & sigma)
{
    int rows = sigma.rows ();
    int cols = sigma.columns ();
    if (cols == 1)
    {
        Vector<float> * result = new Vector<float> (rows);
        for (int i = 0; i < rows; i++) (*result)[i] = gaussian (sigma(i,0));
        return result;
    }
    else if (rows == 1)
    {
        Vector<float> * result = new Vector<float> (cols);
        for (int i = 0; i < cols; i++) (*result)[i] = gaussian (sigma(0,i));
        return result;
    }
    else
    {
        Vector<float> temp (cols);
        for (int i = 0; i < cols; i++) temp[i] = gaussian ();
        return sigma * temp;
    }
}

MatrixResult<float>
grid (int i, int nx, int ny, int nz)
{
    int sx = ny * nz;  // stride x

    // compute xyz in stride order
    Vector3 * result = new Vector3;
    (*result)[0] = ((i / sx) + 0.5f) / nx;  // (i / sx) is an integer operation, so remainder is truncated.
    i %= sx;
    (*result)[1] = ((i / nz) + 0.5f) / ny;
    (*result)[2] = ((i % nz) + 0.5f) / nz;
    return result;
}

MatrixResult<float>
gridRaw (int i, int nx, int ny, int nz)
{
    int sx = ny * nz;  // stride x

    // compute xyz in stride order
    Vector3 * result = new Vector3;
    (*result)[0] = i / sx;
    i %= sx;
    (*result)[1] = i / nz;
    (*result)[2] = i % nz;
    return result;
}


// I/O -----------------------------------------------------------------------

Holder *
holderHelper (vector<Holder *> & holders, const String & fileName, Holder * oldHandle)
{
    vector<Holder *>::iterator it;
    if (oldHandle)
    {
        if (oldHandle->fileName == fileName) return oldHandle;
        for (it = holders.begin (); it != holders.end (); it++)
        {
            if (*it == oldHandle)
            {
                holders.erase (it);
                delete *it;
                break;
            }
        }
    }
    for (it = holders.begin (); it != holders.end (); it++)
    {
        if ((*it)->fileName == fileName) return *it;
    }
    return 0;
}


// class Simulatable ---------------------------------------------------------

Simulatable::~Simulatable ()
{
}

void
Simulatable::clear ()
{
}

void
Simulatable::init ()
{
}

void
Simulatable::integrate ()
{
}

void
Simulatable::update ()
{
}

bool
Simulatable::finalize ()
{
    return true;
}

void
Simulatable::updateDerivative ()
{
}

void
Simulatable::finalizeDerivative ()
{
}

void
Simulatable::snapshot ()
{
}

void
Simulatable::restore ()
{
}

void
Simulatable::pushDerivative ()
{
}

void
Simulatable::multiplyAddToStack (float scalar)
{
}

void
Simulatable::multiply (float scalar)
{
}

void
Simulatable::addToMembers ()
{
}

void
Simulatable::path (String & result)
{
    result = "";
}

void
Simulatable::getNamedValue (const String & name, String & value)
{
}


// class Part ----------------------------------------------------------------

void
Part::setPrevious (Part * previous)
{
}

void
Part::setVisitor (VisitorStep * visitor)
{
}

EventStep *
Part::getEvent ()
{
    return (EventStep *) simulator.currentEvent;
}

void
Part::die ()
{
}

void
Part::enterSimulation ()
{
}

void
Part::leaveSimulation ()
{
}

bool
Part::isFree ()
{
    return true;
}

void
Part::setPart (int i, Part * part)
{
}

Part *
Part::getPart (int i)
{
    return 0;
}

int
Part::getCount (int i)
{
    return 0;
}

void
Part::project (int i, int j, Vector3 & xyz)
{
    xyz[0] = 0;
    xyz[1] = 0;
    xyz[2] = 0;
}

float
Part::getLive ()
{
    return 1;
}

float
Part::getP ()
{
    return 1;
}

void
Part::getXYZ (Vector3 & xyz)
{
    xyz[0] = 0;
    xyz[1] = 0;
    xyz[2] = 0;
}

bool
Part::eventTest (int i)
{
    return false;
}

float
Part::eventDelay (int i)
{
    return -1;  // no care
}

void
Part::setLatch (int i)
{
}

void
Part::finalizeEvent ()
{
}

void
removeMonitor (vector<Part *> & partList, Part * part)
{
    vector<Part *>::iterator it;
    for (it = partList.begin (); it != partList.end (); it++)
    {
        if (*it == part)
        {
            *it = 0;
            break;
        }
    }
}


// class PartTime ------------------------------------------------------------

void
PartTime::setPrevious (Part * previous)
{
    this->previous = previous;
}

void
PartTime::setVisitor (VisitorStep * visitor)
{
    this->visitor = visitor;
}

EventStep *
PartTime::getEvent ()
{
    return (EventStep *) visitor->event;
}

void
PartTime::dequeue ()
{
    // TODO: Need mutex on visitor when modifying its queue, even if it is not part of currentEvent.
    if (simulator.currentEvent == visitor->event)
    {
        // Avoid damaging iterator in visitor
        if (visitor->previous == this) visitor->previous = next;
    }
    if (next) next->setPrevious (previous);
    previous->next = next;
}

void
PartTime::setPeriod (float dt)
{
    dequeue ();
    simulator.enqueue (this, dt);
}


// Wrapper -------------------------------------------------------------------

void
WrapperBase::init ()
{
    population->init ();
}

void
WrapperBase::integrate ()
{
    population->integrate ();
}

void
WrapperBase::update ()
{
    population->update ();
}

bool
WrapperBase::finalize ()
{
    return population->finalize ();  // We depend on explicit code in the top-level finalize() to signal when $n goes to zero.
}

void
WrapperBase::updateDerivative ()
{
    population->updateDerivative ();
}

void
WrapperBase::finalizeDerivative ()
{
    population->finalizeDerivative ();
}

void
WrapperBase::snapshot ()
{
    population->snapshot ();
}

void
WrapperBase::restore ()
{
    population->restore ();
}

void
WrapperBase::pushDerivative ()
{
    population->pushDerivative ();
}

void
WrapperBase::multiplyAddToStack (float scalar)
{
    population->multiplyAddToStack (scalar);
}

void
WrapperBase::multiply (float scalar)
{
    population->multiply (scalar);
}

void
WrapperBase::addToMembers ()
{
    population->addToMembers ();
}


// class Population ----------------------------------------------------------

Population::Population ()
{
    dead        = 0;
    live.before = &live;
    live.after  = &live;
    old         = &live;  // same as old=live.after
}

Population::~Population ()
{
    Part * p = dead;
    while (p)
    {
        Part * next = p->next;
        delete p;
        p = next;
    }
}

void
Population::add (Part * part)
{
}

void
Population::remove (Part * part)
{
    part->next = dead;
    dead = part;
}

Part *
Population::allocate ()
{
    Part * result = 0;

    Part ** p = &dead;
    while (*p)
    {
        if ((*p)->isFree ())
        {
            result = *p;
            result->clear ();
            *p = (*p)->next;  // remove from dead
            break;
        }
        p = & (*p)->next;
    }

    if (! result) result = create ();
    add (result);

    return result;
}

void
Population::resize (int n)
{
}

Population *
Population::getTarget (int i)
{
    return 0;
}

void
Population::connect ()
{
    class KDTreeEntry : public Vector3
    {
    public:
        Part * part;
    };

    Population * A = getTarget (0);
    Population * B = getTarget (1);
    if (A == 0  ||  B == 0) return;  // Nothing to connect. This should never happen, though we might have a unary connection.
    if (A->old == A->live.after  &&  B->old == B->live.after) return;  // Only proceed if there are some new parts. Later, we might consider periodic scanning among old parts.

    // Prepare nearest neighbor search structures on B
    /*
    float radius = getRadius (1);
    int   k      = getK (1);
    KDTreeEntry * entries = 0;
    vector<MatrixAbstract<float> *> entryPointers;
    KDTree NN;
    bool doNN = k  ||  radius;
    if (doNN)
    {
        entries = new KDTreeEntry[Bn];
        entryPointers.resize (Bn);
        Part * b = B->live.after;
        int i = 0;
        while (b != &B->live)
        {
            assert (i < Bn);
            KDTreeEntry & e = entries[i];
            b->getXYZ (e);
            e.part = b;
            entryPointers[i] = &e;
            b = b->after;
            i++;
        }
        NN.set (entryPointers);
        NN.k = k ? k : INT_MAX;
    }
    */

    int Amin = getMin (0);
    int Amax = getMax (0);
    int Bmin = getMin (1);
    int Bmax = getMax (1);

    Part * c = this->create ();

    // Scan AxB
    Part * Alast = A->old;
    Part * Blast = B->live.after;
    bool minSatisfied = false;
    while (! minSatisfied)
    {
        minSatisfied = true;

        // New A with all of B
        Part * a = A->live.after;
        while (a != A->old)
        {
            c->setPart (0, a);
            volatile int Acount;
            if (Amax  ||  Amin) Acount = c->getCount (0);
            if (Amax  &&  Acount >= Amax)  // early out: this part is already full, so skip
            {
                a = a->after;
                continue;
            }

            // Select the subset of B
            /*
            if (doNN)
            {
                c->setPart (1, B->live.after);  // give a dummy B object, in case xyz call breaks rules about only accessing A
                Vector3 xyz;
                c->project (0, 1, xyz);
                vector<MatrixAbstract<float> *> result;
                NN.find (xyz, result);
                int count = result.size ();
                vector<MatrixAbstract<float> *>::iterator it;
                for (it = result.begin (); it != result.end (); it++)
                {
                    Part * b = ((KDTreeEntry *) (*it))->part;

                    c->setPart (1, b);
                    if (Bmax  &&  c->getCount (1) >= Bmax) continue;  // no room in this B
                    float create = c->getP (simulator);
                    if (create <= 0  ||  create < 1  &&  create < uniform ()) continue;  // Yes, we need all 3 conditions. If create is 0 or 1, we do not do a random draw, since it should have no effect.
                    simulator.enqueue (c);
                    c->init (simulator);
                    Acount++;
                    c = this->create ();
                    c->setPart (0, a);

                    if (Amax  &&  Acount >= Amax) break;  // stop scanning B once this A is full
                }
            }
            else
            */
            {
                Part * Bnext = Blast->before;  // will change if we make some connections
                if (Bnext == &B->live) Bnext = Bnext->before;
                Part * b = Blast;
                do
                {
                    b = b->after;
                    if (b == &B->live) b = b->after;

                    c->setPart (1, b);
                    if (Bmax  &&  c->getCount (1) >= Bmax) continue;  // no room in this B
                    float create = c->getP ();
                    if (create <= 0  ||  create < 1  &&  create < uniform ()) continue;  // Yes, we need all 3 conditions. If create is 0 or 1, we do not do a random draw, since it should have no effect.
                    c->enterSimulation ();
                    a->getEvent ()->enqueue (c);
                    c->init ();
                    c = this->create ();
                    c->setPart (0, a);
                    Bnext = b;

                    if (Amax)
                    {
                        if (++Acount >= Amax) break;  // stop scanning B once this A is full
                    }
                }
                while (b != Blast);
                Blast = Bnext;
            }

            //if (Amin  &&  Acount < Amin) minSatisfied = false;
            a = a->after;
        }

        // New B with old A (new A x new B is already covered in case above)
        if (A->old != &A->live)  // There exist some old A
        {
            Part * b = B->live.after;
            while (b != B->old)
            {
                c->setPart (1, b);
                int Bcount;
                if (Bmax  ||  Bmin) Bcount = c->getCount (1);
                if (Bmax  &&  Bcount >= Bmax)
                {
                    b = b->after;
                    continue;
                }

                // TODO: the projection from A to B could be inverted, and another spatial search structure built.
                // For now, we don't use spatial constraints.

                Part * Anext;
                if (Alast == A->old) Anext = A->live.before;
                else                 Anext = Alast->before;
                a = Alast;
                do
                {
                    a = a->after;
                    if (a == &A->live) a = A->old;

                    c->setPart (0, a);
                    if (Amax  &&  c->getCount (0) >= Amax) continue;
                    float create = c->getP ();
                    if (create <= 0  ||  create < 1  &&  create < uniform ()) continue;
                    c->enterSimulation ();
                    b->getEvent ()->enqueue (c);
                    c->init ();
                    c = this->create ();
                    c->setPart (1, b);
                    Anext = a;

                    if (Bmax)
                    {
                        if (++Bcount >= Bmax) break;
                    }
                }
                while (a != Alast);
                Alast = Anext;

                //if (Bmin  &&  Bcount < Bmin) minSatisfied = false;
                b = b->after;
            }
        }

        // Check if minimums have been satisfied for old parts. New parts in both A and B were checked above.
        /*
        if (Amin  &&  minSatisfied)
        {
            Part * a = A->old;
            while (a != &A->live)
            {
                c->setPart (0, a);
                if (c->getCount (0) < Amin)
                {
                    minSatisfied = false;
                    break;
                }
                a = a->after;
            }
        }
        if (Bmin  &&  minSatisfied)
        {
            Part * b = B->old;
            while (b != &B->live)
            {
                c->setPart (1, b);
                if (c->getCount (1) < Bmin)
                {
                    minSatisfied = false;
                    break;
                }
                b = b->after;
            }
        }
        */
    }
    delete c;
    //delete [] entries;
}

void
Population::clearNew ()
{
}

int
Population::getK (int i)
{
    return 0;
}

int
Population::getMax (int i)
{
    return 0;
}

int
Population::getMin (int i)
{
    return 0;
}

float
Population::getRadius (int i)
{
    return 0;
}


// class More ----------------------------------------------------------------

bool
More::operator() (const Event * a, const Event * b) const
{
    return a->t >= b->t;  // If "=" is included in the operator, new entries will get sorted after existing entries at the same point in time.
}


// class Simulator -----------------------------------------------------------

Simulator simulator;

Simulator::Simulator ()
{
    integrator = 0;
    stop = false;

    EventStep * event = new EventStep (0, 1e-4);
    currentEvent = event;
    periods.push_back (event);
}

Simulator::~Simulator ()
{
    for (auto event : periods) delete event;
    if (integrator) delete integrator;
}

void
Simulator::run ()
{
    while (! queueEvent.empty ()  &&  ! stop)
    {
        currentEvent = queueEvent.top ();
        queueEvent.pop ();
        currentEvent->run ();
    }
}

void
Simulator::updatePopulations ()
{
    // Resize populations that have requested it
    for (auto it : queueResize) it.first->resize (it.second);
    queueResize.clear ();

    // Evaluate connection populations that have requested it
    while (! queueConnect.empty ())
    {
        queueConnect.front ()->connect ();
        queueConnect.pop ();
    }

    // Clear new flag from populations that have requested it
    //for (auto it : queueClearNew) it->clearNew ();
    //queueClearNew.clear ();
}

void
Simulator::enqueue (Part * part, float dt)
{
    // find a matching event
    int index = 0;
    int count = periods.size ();
    for (; index < count; index++)
    {
        if (periods[index]->dt >= dt) break;
    }

    EventStep * event;
    if (index < count  &&  periods[index]->dt == dt)
    {
        event = periods[index];
    }
    else
    {
        event = new EventStep (currentEvent->t + dt, dt);
        periods.insert (periods.begin () + index, event);
        queueEvent.push (event);
    }
    event->enqueue (part);
}

void
Simulator::removePeriod (EventStep * event)
{
    vector<EventStep *>::iterator it;
    for (it = periods.begin (); it != periods.end (); it++)
    {
        if (*it == event)
        {
            periods.erase (it);
            break;
        }
    }
    delete event;  // Events still in periods at end will get deleted by dtor.
}

void
Simulator::resize (Population * population, int n)
{
    queueResize.push_back (make_pair (population, n));
}

void
Simulator::connect (Population * population)
{
    queueConnect.push (population);
}

void
Simulator::clearNew (Population * population)
{
    queueClearNew.insert (population);
}


// class Euler ---------------------------------------------------------------

void
Euler::run (Event & event)
{
    event.visit ([](Visitor * visitor)
    {
        visitor->part->integrate ();
    });
}


// class RungeKutta ----------------------------------------------------------

void
RungeKutta::run (Event & event)
{
    // k1
    event.visit ([](Visitor * visitor)
    {
        visitor->part->snapshot ();
        visitor->part->pushDerivative ();
    });

    // k2 and k3
    EventStep & es = (EventStep &) event;
    double t  = es.t;  // Save current values of t and dt
    float  dt = es.dt;
    es.dt /= 2.0f;
    es.t  -= es.dt;  // t is the current point in time, so we must look backward half a timestep
    for (int i = 0; i < 2; i++)
    {
        event.visit ([](Visitor * visitor)
        {
            visitor->part->integrate ();
        });
        event.visit ([](Visitor * visitor)
        {
            visitor->part->updateDerivative ();
        });
        event.visit ([](Visitor * visitor)
        {
            visitor->part->finalizeDerivative ();
            visitor->part->multiplyAddToStack (2.0f);
        });
    }
    es.dt = dt;  // restore original values
    es.t  = t;

    // k4
    event.visit ([](Visitor * visitor)
    {
        visitor->part->integrate ();
    });
    event.visit ([](Visitor * visitor)
    {
        visitor->part->updateDerivative ();
    });
    event.visit ([](Visitor * visitor)
    {
        visitor->part->finalizeDerivative ();
        visitor->part->addToMembers ();  // clears stackDerivative
    });

    // finish
    event.visit ([](Visitor * visitor)
    {
        visitor->part->multiply (1.0 / 6.0);
    });
    event.visit ([](Visitor * visitor)
    {
        visitor->part->integrate ();
    });
    event.visit ([](Visitor * visitor)
    {
        visitor->part->restore ();
    });
}


// class EventStep -----------------------------------------------------------

Event::~Event ()
{
}


// class EventStep -----------------------------------------------------------

EventStep::EventStep (double t, float dt)
:   dt (dt)
{
    this->t = t;
    visitors.push_back (new VisitorStep (this));
}

EventStep::~EventStep ()
{
    for (auto it : visitors) delete it;
}

void
EventStep::run ()
{
    // Update parts
    simulator.integrator->run (*this);
    visit ([](Visitor * visitor)
    {
        visitor->part->update ();
    });
    visit ([](Visitor * visitor)
    {
        if (! visitor->part->finalize ())
        {
            VisitorStep * v = (VisitorStep *) visitor;
            Part * p = visitor->part;  // for convenience
            if (p->next) p->next->setPrevious (v->previous);
            v->previous->next = p->next;
            p->leaveSimulation ();
        }
    });

    simulator.updatePopulations ();
    requeue ();
}

void
EventStep::visit (visitorFunction f)
{
    visitors[0]->visit (f);
}

void
EventStep::requeue ()
{
    if (visitors[0]->queue.next)  // still have instances, so re-queue event
    {
        t += dt;
        simulator.queueEvent.push (this);
    }
    else  // our list of instances is empty, so die
    {
        simulator.removePeriod (this);
    }
}

void
EventStep::enqueue (Part * part)
{
    visitors[0]->enqueue (part);
}


// class EventSpikeSingle ----------------------------------------------------

void
EventSpikeSingle::run ()
{
    target->setLatch (latch);

    simulator.integrator->run (*this);
    visit ([](Visitor * visitor)
    {
        visitor->part->update ();
        visitor->part->finalize ();
        visitor->part->finalizeEvent ();
    });

    delete this;
}

void
EventSpikeSingle::visit (visitorFunction f)
{
    Visitor v (this, target);
    f (&v);
}


// class EventSpikeSingleLatch -----------------------------------------------

void
EventSpikeSingleLatch::run ()
{
    target->setLatch (latch);
    delete this;
}


// class EventSpikeMulti -----------------------------------------------------

void
EventSpikeMulti::run ()
{
    setLatch ();

    simulator.integrator->run (*this);
    visit ([](Visitor * visitor)
    {
        visitor->part->update ();
    });
    visit ([](Visitor * visitor)
    {
        visitor->part->finalize ();
        visitor->part->finalizeEvent ();
        // A part could die during event processing, but it can wait till next EventStep to leave queue.
    });

    delete this;
}

void
EventSpikeMulti::visit (visitorFunction f)
{
    VisitorSpikeMulti v (this);
    v.visit (f);
}

void
EventSpikeMulti::setLatch ()
{
    int i = 0;
    int last = targets->size () - 1;
    while (i <= last)
    {
        Part * target = (*targets)[i];
        if (target)
        {
            target->setLatch (latch);
        }
        else
        {
            (*targets)[i] = (*targets)[last--];
        }
        i++;  // can go past last, but this will cause no harm.
    }
    if ((*targets)[last]) targets->resize (last + 1);
    else                  targets->resize (last);
}


// class EventSpikeMultiLatch ------------------------------------------------

void
EventSpikeMultiLatch::run ()
{
    setLatch ();
    delete this;
}


// class Visitor -------------------------------------------------------------

Visitor::Visitor (Event * event, Part * part)
:   event (event),
    part (part)
{
}

void
Visitor::visit (visitorFunction f)
{
    f (this);
}


// class VisitorStep ---------------------------------------------------------

VisitorStep::VisitorStep (EventStep * event)
:   Visitor (event)
{
    queue.next = 0;
    previous = 0;
}

void
VisitorStep::visit (visitorFunction f)
{
    previous = &queue;
    while (previous->next)
    {
        part = previous->next;
        f (this);
        if (previous->next == part) previous = part;  // Normal advance through list. Check is necessary in case part dequeued while f() was running.
    }
}

void
VisitorStep::enqueue (Part * newPart)
{
    newPart->setVisitor (this);
    if (queue.next) queue.next->setPrevious (newPart);
    newPart->setPrevious (&queue);
    newPart->next = queue.next;
    queue.next = newPart;
}


// class VisitorSpikeMulti ---------------------------------------------------

VisitorSpikeMulti::VisitorSpikeMulti (EventSpikeMulti * event)
:   Visitor (event)
{
}

void
VisitorSpikeMulti::visit (visitorFunction f)
{
    EventSpikeMulti * e = (EventSpikeMulti *) event;
    for (auto target : *e->targets)
    {
        part = target;
        f (this);
    }
}

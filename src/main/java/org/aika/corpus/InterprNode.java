/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aika.corpus;


import org.aika.Activation;
import org.aika.Activation.Key;
import org.aika.Utils;
import org.aika.lattice.Node;

import java.util.*;


/**
 * The {@code InterprNode} class represents a node within the interpretations lattice. Such a node consists of a conjunction of
 * primitive interpretation nodes that are emitted during each activation of a {@code Neuron} (but not the InputNeurons).
 * The primitive nodes them self can consist of a disjunction of other interpretation nodes.
 * For example the primitive interpretation node 9[(1),(6,7,8)] has the primitive node id 9 and consists of the
 * disjunction of the interpretation nodes (1) and (6,7,8). These conjunctions and disjunction in the interpretation
 * representation is necessary since the logic layer underneath each neuron too consists of conjunctions and disjunctions.
 *
 * <p>There may be conflicts between interpretation nodes in the lattice. Conflicts are generated by negative recurrent
 * synapses.
 *
 * @author Lukas Molzberger
 */
public class InterprNode implements Comparable<InterprNode> {

    public static final InterprNode MIN = new InterprNode(null, -1, 0, 0);
    public static final InterprNode MAX = new InterprNode(null, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    public final int primId;
    public int minPrim = -1;
    public int maxPrim = -1;

    public final int id;
    public int length;

    public Map<Activation, InterprNode> orInterprNodes;
    public Set<InterprNode> refByOrInterprNode;
    public InterprNode largestCommonSubset;
    public Set<InterprNode> linkedByLCS;

    /*
    Aika extensively uses graph coloring techniques. When traversing the interpretation node lattice nodes will be
    marked in order to avoid having to visit the same node twice. To avoid having to reset each mark Aika uses the
    counter {@code Document.interprIdCounter} to set a new mark each time.
     */
    private int visitedLinkRelations;
    private int visitedContains;
    private int visitedCollect;
    private int visitedExpandActivations;
    private int visitedRemoveActivations;
    private int visitedIsConflicting;
    private int visitedStoreFinalWeight;
    private int visitedComputeLargestCommonSubset;
    private int visitedComputeLength;
    private int visitedComputeParents;
    private int visitedNumberInnerInputs;
    public int visitedConsistencyCheck;

    int visitedCollectAllConflicting;
    int visitedExpandRefinementRecursiveStep;
    int markedExpandRefinement;
    int visitedCollectConflicts;
    int visitedCheckExcluded;

    public int markedConflict;
    public int markedSelected;
    public int markedExcluded;
    public int markedHasCandidate;

    Boolean hasUnsatisfiedPosFeedbackLinksCache;

    private int numberInnerInputs = 0;

    private int largestCommonSubsetCount = 0;

    public final Document doc;

    boolean isRemoved;
    int removedId;
    static int removedIdCounter = 1;

    private static InterprNode[] EMPTY_INTR_RELS = new InterprNode[0];
    public InterprNode[] parents = EMPTY_INTR_RELS;
    public InterprNode[] children = EMPTY_INTR_RELS;

    public int isConflict = -1;
    public Conflicts conflicts = new Conflicts();

    public NavigableMap<Key, Activation> activations;
    public NavigableSet<Activation> neuronActivations;


    public int refCount = 0;


    public enum Relation {
        EQUALS,
        CONTAINS,
        CONTAINED_IN;

        public boolean compare(InterprNode a, InterprNode b) {
            switch (this) {
                case EQUALS:
                    return a == b;
                case CONTAINS:
                    return a.contains(b, false);
                case CONTAINED_IN:
                    return b.contains(a, false);
                default:
                    return false;
            }
        }
    }

    public InterprNode(Document doc, int primId, int id, int length) {
        this(doc, primId, id);
        this.length = length;
    }


    public InterprNode(Document doc, int primId, int id) {
        this.doc = doc;
        this.primId = primId;
        this.id = id;
    }


    private void computeLargestCommonSubset() {
        int s = orInterprNodes.size();
        int vMin = doc.visitedCounter++;
        List<InterprNode> results = new ArrayList<>();
        for (InterprNode on : orInterprNodes.values()) {
            on.computeLargestCommonSubsetRecursiveStep(results, doc.visitedCounter++, vMin, s, 0);
        }
        setLCS(results.isEmpty() ? null : InterprNode.add(doc, true, results));
    }


    private void computeLargestCommonSubsetIncremental(InterprNode no) {
        if (orInterprNodes.size() == 0) {
            setLCS(no);
            return;
        }
        int vMin = doc.visitedCounter++;
        List<InterprNode> results = new ArrayList<>();
        if(largestCommonSubset != null) {
            largestCommonSubset.computeLargestCommonSubsetRecursiveStep(results, doc.visitedCounter++, vMin, 2, 0);
        }
        no.computeLargestCommonSubsetRecursiveStep(results, doc.visitedCounter++, vMin, 2, 0);
        setLCS(InterprNode.add(doc, true, results));
    }


    private void setLCS(InterprNode lcs) {
        if (largestCommonSubset != null) {
            largestCommonSubset.linkedByLCS.remove(this);
        }
        largestCommonSubset = lcs;
        if (largestCommonSubset != null) {
            if(largestCommonSubset.linkedByLCS == null) {
                largestCommonSubset.linkedByLCS = new TreeSet<>();
            }
            largestCommonSubset.linkedByLCS.add(this);
        }
    }


    private void computeLargestCommonSubsetRecursiveStep(List<InterprNode> results, int v, int vMin, int s, int depth) {
        if (visitedComputeLargestCommonSubset == v) return;
        if (visitedComputeLargestCommonSubset <= vMin) largestCommonSubsetCount = 0;
        visitedComputeLargestCommonSubset = v;
        largestCommonSubsetCount++;

        if(depth > 10) return;

        if (largestCommonSubsetCount == s) {
            results.add(this);
            return;
        }

        for (InterprNode pn : parents) {
            pn.computeLargestCommonSubsetRecursiveStep(results, v, vMin, s, depth + 1);
        }

        if (largestCommonSubset != null) {
            largestCommonSubset.computeLargestCommonSubsetRecursiveStep(results, v, vMin, s, depth + 1);
        }
    }


    public void addOrOption(Activation inputAct, InterprNode n) {
        if (orInterprNodes == null) {
            orInterprNodes = new TreeMap<>();
        }
        computeLargestCommonSubsetIncremental(n);
        orInterprNodes.put(inputAct, n);
        if (n.refByOrInterprNode == null) {
            n.refByOrInterprNode = new TreeSet<>();
        }
        n.refByOrInterprNode.add(this);
    }


    public void removeOrOption(Activation inputAct, InterprNode n) {
        orInterprNodes.remove(inputAct);
        n.refByOrInterprNode.remove(this);
        computeLargestCommonSubset();
    }


    public void countRef() {
        if (isBottom()) return;
        refCount++;
    }


    public void releaseRef() {
        if (isBottom()) return;
        assert refCount > 0;
        refCount--;
        if (refCount == 0) {
            remove();
        }
    }


    void expandActivationsRecursiveStep(Document doc, InterprNode conflict, int v) {
        if (v == visitedExpandActivations) return;
        visitedExpandActivations = v;

        for (Activation act : getActivations()) {
            act.key.n.propagateAddedActivation(doc, act, conflict);
        }

        for (InterprNode p : parents) {
            p.expandActivationsRecursiveStep(doc, conflict, v);
        }
    }


    void removeActivationsRecursiveStep(Document doc, InterprNode conflict, int v) {
        if (v == visitedRemoveActivations) return;
        visitedRemoveActivations = v;

        for (Activation act : getActivations()) {
            if (act.key.o.contains(conflict, false)) {
                Node.removeActivationAndPropagate(doc, act, act.inputs.values());
            }
        }

        if (children != null) {
            for (InterprNode c : children) {
                if (!c.isRemoved) {
                    c.removeActivationsRecursiveStep(doc, conflict, v);
                }
            }
        }
    }


    public Collection<Activation> getActivations() {
        return activations != null ? activations.values() : Collections.emptySet();
    }


    public Collection<Activation> getNeuronActivations() {
        return neuronActivations != null ? neuronActivations : Collections.emptySet();
    }


    public static InterprNode add(Document doc, boolean nonConflicting, InterprNode... input) {
        ArrayList<InterprNode> in = new ArrayList<>();
        for (InterprNode n : input) {
            if (n != null && !n.isBottom()) in.add(n);
        }
        return add(doc, nonConflicting, in);
    }


    public static InterprNode add(Document doc, boolean nonConflicting, List<InterprNode> inputs) {
        if (inputs.size() == 0) return doc.bottom;

        for (Iterator<InterprNode> it = inputs.iterator(); it.hasNext(); ) {
            if (it.next().isBottom()) {
                it.remove();
            }
        }

        if (inputs.size() == 1 || (inputs.size() == 2 && inputs.get(0) == inputs.get(1))) {
            InterprNode n = inputs.get(0);
            if (nonConflicting && n.isConflicting(doc.visitedCounter++)) return null;
            n.countRef();
            return n;
        }

        ArrayList<InterprNode> parents = new ArrayList<>();
        ArrayList<InterprNode> children = new ArrayList<>();
        computeRelations(doc, parents, children, inputs);

        if (parents.size() == 1) {
            InterprNode n = parents.get(0);
            if (nonConflicting && n.isConflicting(doc.visitedCounter++)) return null;
            n.countRef();
            return n;
        }

        if (nonConflicting) {
            for (InterprNode p : parents) {
                if (p.isConflicting(doc.visitedCounter++)) {
                    return null;
                }
            }
        }

        InterprNode n = new InterprNode(doc, -1, doc.interprIdCounter++);

        n.linkRelations(parents, children, doc.visitedCounter++);

        n.length = n.computeLength(doc.visitedCounter++);

        n.minPrim = Integer.MAX_VALUE;
        n.maxPrim = Integer.MIN_VALUE;
        for(InterprNode in: inputs) {
            n.minPrim = Math.min(n.minPrim, in.minPrim);
            n.maxPrim = Math.max(n.maxPrim, in.maxPrim);
        }

        n.countRef();

        return n;
    }


    private static Comparator<InterprNode> LENGTH_COMP = new Comparator<InterprNode>() {
        @Override
        public int compare(InterprNode n1, InterprNode n2) {
            return Integer.compare(n2.length, n1.length);
        }
    };


    private static void computeRelations(Document doc, List<InterprNode> parentsResults, List<InterprNode> childrenResults, List<InterprNode> inputs) {
        if (inputs.isEmpty()) return;
        int v = doc.visitedCounter++;
        int i = 0;
        int s = inputs.size();

        Collections.sort(inputs, LENGTH_COMP);

        if (s == 2 && inputs.get(1).primId >= 0 && inputs.get(1).children.length == 0) {
            parentsResults.addAll(inputs);
            return;
        }

        for(int pass = 0; pass <= 1; pass++) {
            for (InterprNode n : inputs) {
                n.computeParents(parentsResults, v, pass);
            }
            v = doc.visitedCounter++;
        }

        if(parentsResults.size() == 1) return;
        assert parentsResults.size() != 0;

        for (InterprNode n : inputs) {
            n.computeChildren(childrenResults, doc.visitedCounter++, v, inputs.size(), 0);
        }

        inputs.get(0).computeChildren(childrenResults, doc.visitedCounter++, v, inputs.size(), 1);
    }


    private void computeParents(List<InterprNode> parentResults, int v, int pass) {
        if (visitedComputeParents == v || length == 0) return;
        visitedComputeParents = v;

        for (InterprNode pn: parents) {
            pn.computeParents(parentResults, v, pass);
        }

        boolean flag = true;
        for(InterprNode cn: children) {
            if(pass == 0) {
                if (cn.visitedNumberInnerInputs != v) {
                    cn.numberInnerInputs = 0;
                    cn.visitedNumberInnerInputs = v;
                }
                cn.numberInnerInputs++;
            }

            if(cn.numberInnerInputs == cn.parents.length) {
                cn.computeParents(parentResults, v, pass);
                flag = false;
            }
        }

        if(flag && pass == 1) {
            parentResults.add(this);
        }
    }


    private int visitedComputeChildren = -1;
    private int numberOfInputsComputeChildren = 0;


    private void computeChildren(List<InterprNode> childrenResults, int v, int nv, int s, int pass) {
        if (visitedComputeChildren == v) return;

        if (pass == 0) {
            if (visitedComputeChildren <= nv) {
                numberOfInputsComputeChildren = 0;
            }
            numberOfInputsComputeChildren++;
        }

        visitedComputeChildren = v;

        if(pass == 1 && numberOfInputsComputeChildren == s) {
            boolean covered = false;
            for(InterprNode pn: parents) {
                if(pn.numberOfInputsComputeChildren == s) {
                    covered = true;
                    break;
                }
            }

            if(!covered) {
                childrenResults.add(this);
            }
        } else {
            for (InterprNode cn : children) {
                cn.computeChildren(childrenResults, v, nv, s, pass);
            }
        }
    }


    private int computeLength(int v) {
        if (visitedComputeLength == v) return 0;
        visitedComputeLength = v;

        if (primId >= 0) return 1;

        int result = 0;
        for (InterprNode p : parents) {
            result += p.computeLength(v);
        }
        return result;
    }


    private void linkRelations(List<InterprNode> pSet, List<InterprNode> cSet, int v) {
        for (InterprNode p : pSet) {
            addLink(p, this);
        }
        for (InterprNode c : cSet) {
            c.visitedLinkRelations = v;
            addLink(this, c);
        }

        for (InterprNode p : pSet) {
            ArrayList<InterprNode> tmp = new ArrayList<>();
            for (InterprNode c : p.children) {
                if (c.visitedLinkRelations == v) {
                    tmp.add(c);
                }
            }

            for (InterprNode c : tmp) {
                removeLink(p, c);
            }
        }
    }


    private static void addLink(InterprNode a, InterprNode b) {
        a.children = Utils.addToArray(a.children, b);
        b.parents = Utils.addToArray(b.parents, a);
    }


    private static void removeLink(InterprNode a, InterprNode b) {
        a.children = Utils.removeToArray(a.children, b);
        b.parents = Utils.removeToArray(b.parents, a);
    }


    public static InterprNode addPrimitive(Document doc) {
        assert doc != null;

        InterprNode n = new InterprNode(doc, doc.bottom.children.length, doc.interprIdCounter++, 1);

        n.minPrim = n.primId;
        n.maxPrim = n.primId;

        n.countRef();

        addLink(doc.bottom, n);
        return n;
    }


    private void remove() {
        assert !isRemoved;
        isRemoved = true;
        removedId = removedIdCounter++;

        for (InterprNode p : parents) {
            p.children = Utils.removeToArray(p.children, this);
        }
        for (InterprNode c : children) {
            c.parents = Utils.removeToArray(c.parents, this);
        }
        for (InterprNode p : parents) {
            for (InterprNode c : children) {
                if (!c.isLinked(p, doc.visitedCounter++)) {
                    addLink(p, c);
                }
            }
        }

        parents = null;
        children = null;
        conflicts = null;
    }


    public boolean isBottom() {
        return length == 0;
    }


    public boolean contains(boolean dir, InterprNode n, boolean followLCS) {
        boolean r;
        if (!dir) {
            r = contains(n, followLCS);
        } else {
            r = n.contains(this, followLCS);
        }
        return r;
    }


    public boolean contains(InterprNode n, boolean followLCS) {
        return contains(n, followLCS, doc.visitedCounter++);
    }


    private boolean contains(InterprNode n, boolean followLCS, int v) {
        visitedContains = v;

        if(this == n || n.isBottom()) {
            return true;
        }

        if(!followLCS && length <= n.length) return false;

        for(InterprNode p: parents) {
            if(n.maxPrim >= p.minPrim && n.minPrim <= p.maxPrim &&
                    (p.visitedContains != v && p.contains(n, followLCS, v))) {
                return true;
            }
        }

        if(followLCS && largestCommonSubset != null) {
            if(largestCommonSubset.contains(n, followLCS, v)) return true;
        }

        return false;
    }


    private boolean isLinked(InterprNode n, int v) {
        assert visitedContains <= v;
        assert !isRemoved;
        assert !n.isRemoved;

        if(this == n) {
            return true;
        }

        visitedContains = v;
        if(length < n.length) return false;

        for(InterprNode p: parents) {
            if(p.visitedContains != v && p.isLinked(n, v)) return true;
        }
        return false;
    }


    void collectPrimitiveNodes(Set<InterprNode> results, int v) {
        if(v == visitedCollect) return;
        visitedCollect = v;

        if(primId >= 0) {
            results.add(this);
        } else {
            for(InterprNode n: parents) {
                n.collectPrimitiveNodes(results, v);
            }
        }
    }


    public boolean isConflicting(int v) {
        if (isConflict >= 0) {
            return true;
        } else if(conflictsAllowed()) {
            if(visitedIsConflicting == v) return false;
            visitedIsConflicting = v;

            for(InterprNode p : parents) {
                if(p.isConflicting(v)) {
                    return true;
                }
            }
        }
        return false;
    }


    private boolean conflictsAllowed() {
        return activations == null || activations.isEmpty();
    }


    void storeFinalWeight(int v) {
        if(visitedStoreFinalWeight == v) return;
        visitedStoreFinalWeight = v;

        for(Activation act: getNeuronActivations()) {
            act.finalState = act.rounds.getLast();
        }

        for(InterprNode cn: children) {
            cn.storeFinalWeight(v);
        }
    }


    public String toString() {
        return toString(false);
    }


    private String toString(boolean level) {
        SortedSet<InterprNode> ids = new TreeSet<>();
        collectPrimitiveNodes(ids, doc.visitedCounter++);

        StringBuilder sb = new StringBuilder();
        sb.append("(");
        boolean f1 = true;
        for(InterprNode n: ids) {
            if(!f1) sb.append(",");
            f1 = false;
            sb.append(n.primId);
            if(!level && n.orInterprNodes != null) {
                sb.append("[");
                boolean f2 = true;
                for(InterprNode on: n.orInterprNodes.values()) {
                    if(!f2) sb.append(",");
                    f2 = false;
                    sb.append(on.toString(true));
                }
                sb.append("]");
            }
        }

        sb.append(")");
        return sb.toString();
    }


    @Override
    public int compareTo(InterprNode n) {
        int r = Integer.compare(length, n.length);
        if(r != 0) return r;
        return Integer.compare(id, n.id);
    }


    public static int compare(InterprNode oa, InterprNode ob) {
        if(oa == ob) return 0;
        if(oa == null && ob != null) return -1;
        if(oa != null && ob == null) return 1;
        return oa.compareTo(ob);
    }
}

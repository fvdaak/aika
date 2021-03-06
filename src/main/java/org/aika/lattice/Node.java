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
package org.aika.lattice;


import org.aika.*;
import org.aika.lattice.NodeActivation.Key;
import org.aika.corpus.Document;
import org.aika.corpus.InterprNode;
import org.aika.corpus.Range;
import org.aika.lattice.AndNode.Refinement;
import org.aika.lattice.OrNode.OrEntry;
import org.aika.neuron.INeuron;
import org.aika.neuron.Synapse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;


/**
 * The {@code Node} class is the abstract class for all the boolean logic nodes underneath the neural network layer.
 * These nodes form a boolean representation for all the neurons of the neural network. Whenever changes occur to the
 * synapse weights in the neural layer, then the structure of the boolean representation needs to be adjusted. Several
 * neurons, however, might share common substructures in this boolean representation. The {@code InputNode} and
 * the {@code AndNode} classes together form a pattern lattice, containing all possible substructures of any
 * given conjunction. For example if we have the conjunction ABCD where A, B, C, D are the inputs then the
 * pattern lattice will contain the nodes ABCD, ABC, ABD, ACD, BCD, AB, AC, AD, BC, BD, CD, A, B, C, D. The class
 * {@code OrNode} is a disjunction of either input-nodes or and-nodes. The or-node is connected with one of
 * the neurons.
 * <p>
 * <p>Each logic node has a set of activations. The activations are stored in the thread local data structure
 * {@code ThreadState}.
 *
 * @author Lukas Molzberger
 */
public abstract class Node<T extends Node, A extends NodeActivation<T>> extends AbstractNode<Provider<T>> implements Comparable<Node> {
    public static int minFrequency = 5;
    public static int MAX_RID = 25;

    public static final Node MIN_NODE = new InputNode();
    public static final Node MAX_NODE = new InputNode();

    private static final Logger log = LoggerFactory.getLogger(Node.class);

    TreeMap<ReverseAndRefinement, Refinement> reverseAndChildren;
    TreeMap<Refinement, Provider<AndNode>> andChildren;
    TreeSet<OrEntry> orChildren;
    TreeSet<OrEntry> allOrChildren;

    public int level;

    public volatile int frequency;
    public volatile double nullHypFreq;
    public volatile double oldNullHypFreq;

    public boolean isBlocked;

    public boolean endRequired;
    public boolean ridRequired;


    volatile boolean isRemoved;
    volatile int isRemovedId;
    volatile static int isRemovedIdCounter = 0;

    public volatile boolean frequencyHasChanged = true;
    public volatile int nOffset;

    public volatile int sizeSum = 0;
    public volatile int instanceSum = 0;


    // Only childrens are locked.
    public ReadWriteLock lock = new ReadWriteLock();


    public boolean isQueued = false;
    public long queueId;

    public static long visitedCounter = 1;

    public ThreadState<T, A>[] threads;

    /**
     * The {@code ThreadState} is a thread local data structure containing the activations of a single document for
     * a specific logic node.
     */
    public static class ThreadState<T extends Node, A extends NodeActivation<T>> {
        public long lastUsed;

        public TreeMap<Key, A> activations;
        public TreeMap<Key, A> activationsEnd;
        public TreeMap<Key, A> activationsRid;

        public NavigableMap<Key, Set<NodeActivation<?>>> added;
        public NavigableMap<Key, RemovedEntry> removed;
        long visitedNeuronRefsChange = -1;
        public long visitedAllowedOption = -1;
        public long visitedComputeWeight = -1;

        private RidVisited nullRidVisited;
        private RidVisited[] ridVisited = new RidVisited[2 * MAX_RID];

        public ThreadState(boolean endRequired, boolean ridRequired) {
            activations = new TreeMap<>(BEGIN_COMP);
            activationsEnd = endRequired ? new TreeMap<>(END_COMP) : null;
            activationsRid = ridRequired ? new TreeMap<>(RID_COMP) : null;

            added = new TreeMap<>();
            removed = new TreeMap<>();
        }

        public RidVisited lookupVisited(Integer offset) throws RidOutOfRange {
            if (offset != null && (offset >= MAX_RID || offset <= -MAX_RID)) {
                log.warn("RID too large:" + offset);
                throw new RidOutOfRange("RID too large:" + offset);
            }

            if (offset == null) {
                if (nullRidVisited == null) {
                    nullRidVisited = new RidVisited();
                }
                return nullRidVisited;
            } else {
                RidVisited v = ridVisited[offset + MAX_RID];
                if (v == null) {
                    v = new RidVisited();
                    ridVisited[offset + MAX_RID] = v;
                }
                return v;
            }
        }

        public static class RidOutOfRange extends Exception {
            public RidOutOfRange(String s) {
                super(s);
            }
        }
    }


    /**
     * Aika extensively uses graph coloring techniques. When traversing the logic node lattice, nodes will be
     * marked in order to avoid having to visit the same node twice. To avoid having to reset each mark Aika uses the
     * counter {@code Node.visitedCounter} to set a new mark each time.
     */
    public static class RidVisited {
        public long computeParents = -1;
        public long outputNode = -1;
        public long adjust = -1;
    }


    public ThreadState<T, A> getThreadState(int threadId, boolean create) {
        ThreadState<T, A> th = threads[threadId];
        if (th == null) {
            if (!create) return null;

            th = new ThreadState(endRequired, ridRequired);
            threads[threadId] = th;
        }
        th.lastUsed = Document.docIdCounter.get();
        return th;
    }


    /**
     * Propagate an activation to the next node or the next neuron that is depending on the current node.
     *
     * @param doc
     * @param act
     * @param conflict
     */
    public abstract void propagateAddedActivation(Document doc, A act, InterprNode conflict);

    public abstract void propagateRemovedActivation(Document doc, NodeActivation act);

    public abstract boolean isAllowedOption(int threadId, InterprNode n, NodeActivation<?> act, long v);

    public abstract void cleanup(Model m);

    abstract A createActivation(Document doc, Key ak, boolean isTrainingAct);

    abstract void deleteActivation(Document doc, A act);

    public abstract double computeSynapseWeightSum(Integer offset, INeuron n);

    public abstract String logicToString();

    abstract void apply(Document doc, A act, InterprNode conflict);

    public abstract void discover(Document doc, NodeActivation<T> act);

    abstract Collection<Refinement> collectNodeAndRefinements(Refinement newRef);

    abstract boolean hasSupport(A act);

    public abstract void computeNullHyp(Model m);

    abstract boolean isExpandable(boolean checkFrequency);

    abstract boolean contains(Refinement ref);

    protected Node() {
    }


    public Node(Model m, int level) {
        threads = new ThreadState[m.numberOfThreads];
        provider = new Provider(m, this);
        this.level = level;
        if (m != null) {
            nOffset = m.numberOfPositions;
        }
        provider.setModified();
    }


    public static final Comparator<NodeActivation.Key> BEGIN_COMP = new Comparator<Key>() {
        @Override
        public int compare(Key k1, Key k2) {
            int r;
            r = Range.compare(k1.r, k2.r, false);
            if (r != 0) return r;
            r = Utils.compareInteger(k1.rid, k2.rid);
            if (r != 0) return r;
            return InterprNode.compare(k1.o, k2.o);
        }
    };


    public static final Comparator<NodeActivation.Key> END_COMP = new Comparator<Key>() {
        @Override
        public int compare(Key k1, Key k2) {
            int r;
            r = Range.compare(k1.r, k2.r, true);
            if (r != 0) return r;
            r = Utils.compareInteger(k1.rid, k2.rid);
            if (r != 0) return r;
            return InterprNode.compare(k1.o, k2.o);
        }
    };


    public static final Comparator<NodeActivation.Key> RID_COMP = new Comparator<Key>() {
        @Override
        public int compare(Key k1, Key k2) {
            int r;
            r = Utils.compareInteger(k1.rid, k2.rid);
            if (r != 0) return r;
            r = Range.compare(k1.r, k2.r, false);
            if (r != 0) return r;
            return InterprNode.compare(k1.o, k2.o);
        }
    };


    void addOrChild(OrEntry oe, boolean all) {
        lock.acquireWriteLock();
        if(all) {
            if (allOrChildren == null) {
                allOrChildren = new TreeSet<>();
            }
            allOrChildren.add(oe);
        } else {
            if (orChildren == null) {
                orChildren = new TreeSet<>();
            }
            orChildren.add(oe);
        }
        lock.releaseWriteLock();
    }


    void removeOrChild(OrEntry oe, boolean all) {
        lock.acquireWriteLock();
        if(all) {
            if (allOrChildren != null) {
                allOrChildren.remove(oe);
                if (allOrChildren.isEmpty()) {
                    allOrChildren = null;
                }
            }
        } else {
            if (orChildren != null) {
                orChildren.remove(oe);
                if (orChildren.isEmpty()) {
                    orChildren = null;
                }
            }
        }
        lock.releaseWriteLock();
    }


    void addAndChild(Refinement ref, Provider<AndNode> child) {
        if (andChildren == null) {
            andChildren = new TreeMap<>();
            reverseAndChildren = new TreeMap<>();
        }

        Provider<AndNode> n = andChildren.put(ref, child);
        if (n != null) {
            System.out.println();
        }

        assert n == null;
        reverseAndChildren.put(new ReverseAndRefinement(child, ref.rid, 0), ref);
    }


    void removeAndChild(Refinement ref) {
        if (andChildren != null) {
            Provider<AndNode> child = andChildren.remove(ref);
            reverseAndChildren.remove(new ReverseAndRefinement(child, ref.rid, 0));

            if (andChildren.isEmpty()) {
                andChildren = null;
                reverseAndChildren = null;
            }
        }
    }


    public void count(int threadId) {
        ThreadState<T, A> ts = getThreadState(threadId, false);
        if (ts == null) return;

        for (NodeActivation<T> act : ts.activations.values()) {
            frequency++;
            frequencyHasChanged = true;

            sizeSum += act.key.r.end == null || act.key.r.begin == null || act.key.r.end == Integer.MAX_VALUE ? 1 : Math.max(1, act.key.r.end - act.key.r.begin);
            instanceSum++;
        }
    }


    A processAddedActivation(Document doc, Key<T> ak, Collection<NodeActivation> inputActs, boolean isTrainingAct) {
        if (Document.APPLY_DEBUG_OUTPUT) {
            log.info("add: " + ak + " - " + ak.n);
        }

        A act = NodeActivation.get(doc, (T) this, ak);
        if (act == null) {
            act = createActivation(doc, ak, isTrainingAct);

            register(act, doc);

            act.link(inputActs);

            if (!isTrainingAct) {
                propagateAddedActivation(doc, act, null);
            }
        } else {
            act.link(inputActs);
        }

        return act;
    }


    void processRemovedActivation(Document doc, A act, Collection<NodeActivation> inputActs) {
        if (act.isRemoved) {
            unregister(act, doc);
            deleteActivation(doc, act);

            propagateRemovedActivation(doc, act);

            act.key.releaseRef();
        }

        // TODO: check unlinkNeuronRelations symmetry
        act.unlink(inputActs);
    }


    public void register(A act, Document doc) {
        Key ak = act.key;

        ThreadState th = ak.n.getThreadState(doc.threadId, true);
        if (th.activations.isEmpty()) {
            (act.isTrainingAct ? doc.activatedNodesForTraining : doc.activatedNodes).add(ak.n);
        }
        th.activations.put(ak, act);

        TreeMap<Key, NodeActivation> actEnd = th.activationsEnd;
        if (actEnd != null) actEnd.put(ak, act);

        TreeMap<Key, NodeActivation> actRid = th.activationsRid;
        if (actRid != null) actRid.put(ak, act);

        if (ak.o.activations == null) {
            ak.o.activations = new TreeMap<>();
        }
        ak.o.activations.put(ak, act);

        ak.n.lastUsedDocumentId = doc.id;

        if (ak.rid != null) {
            doc.activationsByRid.put(ak, act);
        }
    }


    public void unregister(A act, Document doc) {
        Key ak = act.key;

        assert !ak.o.activations.isEmpty();

        Node.ThreadState th = ak.n.getThreadState(doc.threadId, true);

        th.activations.remove(ak);

        TreeMap<Key, NodeActivation> actEnd = th.activationsEnd;
        if (actEnd != null) actEnd.remove(ak);

        TreeMap<Key, NodeActivation> actRid = th.activationsRid;
        if (actRid != null) actRid.remove(ak);

        if (th.activations.isEmpty()) {
            (act.isTrainingAct ? doc.activatedNodesForTraining : doc.activatedNodes).remove(ak.n);
        }

        ak.o.activations.remove(ak);

        if (ak.rid != null) {
            doc.activationsByRid.remove(ak);
        }
    }


    /**
     * Process all added or removed activation for this logic node.
     *
     * @param doc
     */
    public void processChanges(Document doc) {
        ThreadState th = getThreadState(doc.threadId, true);
        NavigableMap<Key<T>, Collection<NodeActivation>> tmpAdded = th.added;
        NavigableMap<Key<T>, RemovedEntry> tmpRemoved = th.removed;

        th.added = new TreeMap<>();
        th.removed = new TreeMap<>();

        for (Iterator<Map.Entry<Key<T>, RemovedEntry>> it = tmpRemoved.entrySet().iterator(); it.hasNext(); ) {
            Key akr = it.next().getKey();
            boolean remove = false;
            for (Key aka : tmpAdded.keySet()) {
                if (aka.o == akr.o && aka.rid == akr.rid && aka.r == akr.r)
                    remove = true;
            }
            if (remove) it.remove();
        }

        for (RemovedEntry<T, A> re : tmpRemoved.values()) {
            if (!hasSupport(re.act)) {
                re.act.removedId = NodeActivation.removedIdCounter++;
                re.act.isRemoved = true;
            }
        }

        for (RemovedEntry<T, A> re : tmpRemoved.values()) {
            processRemovedActivation(doc, re.act, re.iActs);
        }

        for (Map.Entry<Key<T>, Collection<NodeActivation>> me : tmpAdded.entrySet()) {
            processAddedActivation(doc, me.getKey(), me.getValue(), false);
        }
    }


    /**
     * Add a new activation to this logic node and further propagate this activation through the network.
     * This activation, however, will not be added immediately. This method only adds a request to the activations
     * queue in the document. The activation will be added when the method {@code Node.processChanges(Document doc)}
     * is called.
     *
     * @param doc
     * @param ak
     * @param inputActs
     */
    public static <T extends Node, A extends NodeActivation<T>> void addActivationAndPropagate(Document doc, Key<T> ak, Collection<NodeActivation<?>> inputActs) {
        ThreadState<T, A> th = ak.n.getThreadState(doc.threadId, true);
        Set<NodeActivation<?>> iActs = th.added.get(ak);
        if (iActs == null) {
            iActs = new TreeSet<>();
            th.added.put(ak, iActs);
        }
        iActs.addAll(inputActs);
        doc.queue.add(ak.n);
    }

    /*
    First remove the inputs from the given activation. Only if, depending on the node type, insufficient support exists for this activation, then actually remove it.
     */
    public static <T extends Node, A extends NodeActivation<T>> void removeActivationAndPropagate(Document doc, A act, Collection<NodeActivation<?>> inputActs) {
        if (act == null || act.isRemoved) return;

        ThreadState<T, A> th = act.key.n.getThreadState(doc.threadId, true);
        RemovedEntry re = th.removed.get(act.key);
        if (re == null) {
            re = new RemovedEntry();
            re.act = act;
            th.removed.put(act.key, re);
        }
        re.iActs.addAll(inputActs);
        doc.queue.add(act.key.n);
    }


    public Collection<A> getActivations(Document doc) {
        ThreadState<T, A> th = getThreadState(doc.threadId, false);
        if (th == null) return Collections.EMPTY_LIST;
        return th.activations.values();
    }


    public synchronized A getFirstActivation(Document doc) {
        ThreadState<T, A> th = getThreadState(doc.threadId, false);
        if (th == null || th.activations.isEmpty()) return null;
        return th.activations.firstEntry().getValue();
    }


    public void clearActivations(Document doc) {
        clearActivations(doc.threadId);
    }

    public void clearActivations(int threadId) {
        ThreadState th = getThreadState(threadId, false);
        if (th == null) return;
        th.activations.clear();

        if (th.activationsEnd != null) th.activationsEnd.clear();
        if (th.activationsRid != null) th.activationsRid.clear();

        th.added.clear();
        th.removed.clear();
    }


    public void clearActivations(Model m) {
        for (int i = 0; i < m.numberOfThreads; i++) {
            clearActivations(i);
        }
    }


    public boolean isFrequent() {
        return frequency >= minFrequency;
    }


    public boolean isPublic() {
        return this instanceof AndNode && orChildren != null && !orChildren.isEmpty();
    }


    boolean computeAndParents(Model m, int threadId, Integer offset, SortedSet<Refinement> inputs, Map<Refinement, Provider<? extends Node>> parents, boolean discoverPatterns, long v) throws ThreadState.RidOutOfRange {
        RidVisited nv = getThreadState(threadId, true).lookupVisited(offset);
        if (nv.computeParents == v) return true;
        nv.computeParents = v;

        if (inputs.size() == 1) {
            parents.put(inputs.first(), provider);
            return true;
        }

        for (Refinement ref : inputs) {
            SortedSet<Refinement> childInputs = new TreeSet<>(inputs);
            childInputs.remove(ref);

            Refinement nRef = new Refinement(ref.getRelativePosition(), offset, ref.input);
            lock.acquireReadLock();
            Provider<AndNode> cp = andChildren != null ? andChildren.get(nRef) : null;
            lock.releaseReadLock();

            if (cp == null) {
                if (discoverPatterns) return false;
                cp = AndNode.createNextLevelNode(m, threadId, this, nRef, discoverPatterns).provider;
                if (cp == null) return false;
            }

            Integer nOffset = Utils.nullSafeMin(ref.getRelativePosition(), offset);
            if (!cp.get().computeAndParents(m, threadId, nOffset, childInputs, parents, discoverPatterns, v)) {
                return false;
            }
        }
        return true;
    }


    void removeFromNextLevel(Document doc, NodeActivation iAct) {
        AndNode.removeActivation(doc, iAct);

        if (orChildren != null) {
            for (OrEntry oe : orChildren) {
                oe.node.get().removeActivation(doc, oe.ridOffset, iAct);
            }
        }
    }


    void remove(Model m) {
        assert !isRemoved;

        lock.acquireWriteLock();
        provider.setModified();
        while (andChildren != null && !andChildren.isEmpty()) {
            andChildren.firstEntry().getValue().get().remove(m);
        }

        while (orChildren != null && !orChildren.isEmpty()) {
            orChildren.pollFirst().node.get().remove(m);
        }
        lock.releaseWriteLock();

        clearActivations(m);

        isRemoved = true;
        isRemovedId = isRemovedIdCounter++;
    }


    Provider<AndNode> getAndChild(Refinement ref) {
        lock.acquireReadLock();
        Provider<AndNode> result = andChildren != null ? andChildren.get(ref) : null;
        lock.releaseReadLock();
        return result;
    }


    public boolean isCovered(int threadId, Integer offset, long v) throws ThreadState.RidOutOfRange {
        return false;
    }


    public boolean isRequired() {
        return !allOrChildren.isEmpty();
    }


    public String getNeuronLabel() {
        return "";
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getNeuronLabel());
        sb.append(" - ");
        sb.append(logicToString());
        sb.append(" - ");
        sb.append(weightsToString());
        return sb.toString();
    }


    public String weightsToString() {
        return "";
    }


    public int compareTo(Node n) {
        if (this == n) return 0;
        if (this == MIN_NODE) return -1;
        if (n == MIN_NODE) return 1;
        if (this == MAX_NODE) return 1;
        if (n == MAX_NODE) return -1;

        return provider.compareTo(n.provider);
    }


    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(level);

        out.writeInt(frequency);
        out.writeDouble(nullHypFreq);
        out.writeDouble(oldNullHypFreq);

        out.writeBoolean(isBlocked);

        out.writeBoolean(endRequired);
        out.writeBoolean(ridRequired);

        out.writeBoolean(frequencyHasChanged);
        out.writeInt(nOffset);

        out.writeInt(sizeSum);
        out.writeInt(instanceSum);

        if (andChildren != null) {
            out.writeInt(andChildren.size());
            for (Map.Entry<Refinement, Provider<AndNode>> me : andChildren.entrySet()) {
                me.getKey().write(out);
                out.writeInt(me.getValue().id);
            }
        } else {
            out.writeInt(0);
        }

        if (orChildren != null) {
            out.writeInt(orChildren.size());
            for (OrEntry oe : orChildren) {
                oe.write(out);
            }
        } else {
            out.writeInt(0);
        }
    }


    @Override
    public void readFields(DataInput in, Model m) throws IOException {
        level = in.readInt();

        frequency = in.readInt();
        nullHypFreq = in.readDouble();
        oldNullHypFreq = in.readDouble();

        isBlocked = in.readBoolean();

        endRequired = in.readBoolean();
        ridRequired = in.readBoolean();

        frequencyHasChanged = in.readBoolean();
        nOffset = in.readInt();

        sizeSum = in.readInt();
        instanceSum = in.readInt();

        int s = in.readInt();
        for (int i = 0; i < s; i++) {
            addAndChild(Refinement.read(in, m), m.lookupNodeProvider(in.readInt()));
        }

        s = in.readInt();
        for (int i = 0; i < s; i++) {
            if (orChildren == null) {
                orChildren = new TreeSet<>();
            }
            orChildren.add(OrEntry.read(in, m));
        }

        threads = new ThreadState[m.numberOfThreads];
    }


    public static Node readNode(DataInput in, Provider p) throws IOException {
        String type = in.readUTF();
        Node n = null;
        switch (type) {
            case "I":
                n = new InputNode();
                break;
            case "A":
                n = new AndNode();
                break;
            case "O":
                n = new OrNode();
                break;
        }
        n.provider = p;

        n.readFields(in, p.m);
        return n;
    }


    private static class RemovedEntry<T extends Node, A extends NodeActivation<T>> {
        A act;
        Set<NodeActivation> iActs = new TreeSet<>();
    }


    static class ReverseAndRefinement implements Comparable<ReverseAndRefinement> {
        boolean dir;
        Provider node;

        public ReverseAndRefinement(Provider n, Integer a, Integer b) {
            this.node = n;
            this.dir = Utils.compareNullSafe(a, b);
        }

        @Override
        public int compareTo(ReverseAndRefinement rar) {
            int r = node.compareTo(rar.node);
            if (r != 0) return r;
            return Boolean.compare(dir, rar.dir);
        }
    }
}

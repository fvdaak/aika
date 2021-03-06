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


import org.aika.Neuron;
import org.aika.lattice.NodeActivation.Key;
import org.aika.Model;
import org.aika.Provider;
import org.aika.Utils;
import org.aika.corpus.Document;
import org.aika.corpus.InterprNode;
import org.aika.corpus.Range;
import org.aika.neuron.INeuron;
import org.aika.neuron.Synapse;
import org.apache.commons.math3.distribution.BinomialDistribution;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

/**
 * The {@code InputNode} and the {@code AndNode} classes together form a pattern lattice, containing all
 * possible substructures of any given conjunction. For example if we have the conjunction ABCD where A, B, C, D are
 * the inputs, then the pattern lattice will contain the nodes ABCD, ABC, ABD, ACD, BCD, AB, AC, AD, BC, BD, CD,
 * A, B, C, D. The pattern lattice is organized in layers, where each layer only contains conjunctions/patterns of the
 * same size. These layers are connected through refinements. For example the and-node
 * ABD on layer 3 is connected to the and-node ABCD on layer 4 via the refinement C.
 *
 * @author Lukas Molzberger
 */
public class AndNode extends Node<AndNode, NodeActivation<AndNode>> {

    private static double SIGNIFICANCE_THRESHOLD = 0.98;
    public static int MAX_POS_NODES = 4;
    public static int MAX_RID_RANGE = 5;

    SortedMap<Refinement, Provider<? extends Node>> parents = new TreeMap<>();


    public volatile int numberOfPositionsNotify;
    private volatile int frequencyNotify;

    private double weight = -1;


    public AndNode() {}


    public AndNode(Model m, int level, SortedMap<Refinement, Provider<? extends Node>> parents) {
        super(m, level);
        this.parents = parents;

        m.stat.nodes++;
        m.stat.nodesPerLevel[level]++;

        ridRequired = false;

        for(Map.Entry<Refinement, Provider<? extends Node>> me: parents.entrySet()) {
            Refinement ref = me.getKey();
            Node pn = me.getValue().get();

            pn.addAndChild(ref, provider);
            pn.provider.setModified();

            if(ref.rid != null) ridRequired = true;
        }

        endRequired = false;
    }


    @Override
    public boolean isAllowedOption(int threadId, InterprNode n, NodeActivation<?> act, long v) {
        ThreadState th = getThreadState(threadId, true);
        if(th.visitedAllowedOption == v) return false;
        th.visitedAllowedOption = v;

        for(NodeActivation pAct: act.inputs.values()) {
            if(pAct.key.n.isAllowedOption(threadId, n, pAct, v)) return true;
        }
        return false;
    }


    NodeActivation<AndNode> processAddedActivation(Document doc, Key<AndNode> ak, Collection<NodeActivation> inputActs, boolean isTrainingAct) {
        int s = 0;
        for(NodeActivation iAct: inputActs) {
            if(!iAct.isRemoved) s++;
        }
        if(s != level) {
            return null;
        }

        return super.processAddedActivation(doc, ak, inputActs, isTrainingAct);
    }


    void addActivation(Document doc, Key ak, Collection<NodeActivation<?>> directInputActs) {
        Node.addActivationAndPropagate(doc, ak, directInputActs);
    }


    static void removeActivation(Document doc, NodeActivation<?> iAct) {
        for(NodeActivation act: iAct.outputs.values()) {
            if(act.key.n instanceof AndNode) {
                Node.removeActivationAndPropagate(doc, act, Collections.singleton(iAct));
            }
        }
    }


    public void propagateAddedActivation(Document doc, NodeActivation act, InterprNode removedConflict) {
        apply(doc, act, removedConflict);
    }


    public void propagateRemovedActivation(Document doc, NodeActivation act) {
        removeFromNextLevel(doc, act);
    }


    @Override
    boolean hasSupport(NodeActivation<AndNode> act) {
        int expected = parents.size();

        int support = 0;
        NodeActivation lastAct = null;
        for(NodeActivation iAct: act.inputs.values()) {
            if(!iAct.isRemoved && (lastAct == null || lastAct.key.n != iAct.key.n)) {
                support++;
            }
            lastAct = iAct;
        }
        assert support <= expected;
        return support == expected;
    }


    @Override
    public void computeNullHyp(Model m) {
        double avgSize = sizeSum / instanceSum;
        double n = (double) (m.numberOfPositions - nOffset) / avgSize;

        double nullHyp = 0.0;
        for(Map.Entry<Refinement, Provider<? extends Node>> me: parents.entrySet()) {
            Node pn = me.getValue().get();
            InputNode in = me.getKey().input.get();
            double inputNA = (double) (m.numberOfPositions - in.nOffset) / avgSize;
            double inputNB = (double) (m.numberOfPositions - pn.nOffset) / avgSize;

            double nh = Math.min(1.0, in.frequency / inputNA) * Math.min(1.0, Math.max(pn.frequency, pn.nullHypFreq) / inputNB);
            nullHyp = Math.max(nullHyp, nh);
        }

        nullHypFreq = nullHyp * n;
    }


    public void updateWeight(Document doc, long v) {
        ThreadState th = getThreadState(doc.threadId, true);
        Model m = doc.m;
        if(isBlocked ||
                (m.numberOfPositions - nOffset) == 0 ||
                frequency < Node.minFrequency ||
                th.visitedComputeWeight == v ||
                (numberOfPositionsNotify > m.numberOfPositions && frequencyNotify > frequency && Math.abs(nullHypFreq - oldNullHypFreq) < 0.01)
                ) {
            return;
        }

        th.visitedComputeWeight = v;

        double avgSize = sizeSum / instanceSum;
        double n = (double) (m.numberOfPositions - nOffset) / avgSize;

        doc.m.numberOfPositionsQueue.remove(provider);
        numberOfPositionsNotify = computeNotify(n) + m.numberOfPositions;
        doc.m.numberOfPositionsQueue.add(provider);

        BinomialDistribution binDist = new BinomialDistribution(null, (int)Math.round(n), nullHypFreq / n);

        weight = binDist.cumulativeProbability(frequency - 1);

        frequencyNotify = computeNotify(frequency) + frequency;
        oldNullHypFreq = nullHypFreq;

        if(weight >= SIGNIFICANCE_THRESHOLD) {
//            checkSignificantPattern(t);
        }
    }


    public int computeNotify(double x) {
        return 1 + (int) Math.floor(Math.pow(x, 1.15) - x);
    }


    @Override
    public void cleanup(Model m) {
        if(!isRemoved && !isFrequent() && !isRequired()) {
            remove(m);

            for(Provider<? extends Node> p: parents.values()) {
                p.get().cleanup(m);
            }
        }
    }


    @Override
    void apply(Document doc, NodeActivation<AndNode> act, InterprNode removedConflict) {

        // Check if the activation has been deleted in the meantime.
        if(act.isRemoved) {
            return;
        }

        for(NodeActivation<?> pAct: act.inputs.values()) {
            Node<?, NodeActivation<?>> pn = pAct.key.n;
            pn.lock.acquireReadLock();
            Refinement ref = pn.reverseAndChildren.get(new ReverseAndRefinement(act.key.n.provider, act.key.rid, pAct.key.rid));
            if(ref != null) {
                for (NodeActivation secondAct : pAct.outputs.values()) {
                    if (act != secondAct && !secondAct.isRemoved) {
                        Refinement secondRef = pn.reverseAndChildren.get(new ReverseAndRefinement(secondAct.key.n.provider, secondAct.key.rid, pAct.key.rid));
                        if (secondRef != null) {
                            Refinement nRef = new Refinement(secondRef.rid, ref.getOffset(), secondRef.input);

                            Provider<AndNode> nlp = getAndChild(nRef);
                            if (nlp != null) {
                                addNextLevelActivation(doc, act, secondAct, nlp, removedConflict);
                            }
                        }
                    }
                }
            }
            pn.lock.releaseReadLock();
        }

        if(removedConflict == null) {
            OrNode.processCandidate(doc, this, act, false);
        }
    }


    @Override
    public void discover(Document doc, NodeActivation<AndNode> act) {
        if(!isExpandable(true)) return;

        for(NodeActivation<?> pAct: act.inputs.values()) {
            Node<?, NodeActivation<?>> pn = pAct.key.n;
            pn.lock.acquireReadLock();
            Refinement ref = pn.reverseAndChildren.get(new ReverseAndRefinement(act.key.n.provider, act.key.rid, pAct.key.rid));
            for(NodeActivation secondAct: pAct.outputs.values()) {
                if(secondAct.key.n instanceof AndNode) {
                    Node secondNode = secondAct.key.n;
                    Integer ridDelta = Utils.nullSafeSub(act.key.rid, false, secondAct.key.rid, false);
                    if (act != secondAct &&
                            !secondNode.isBlocked &&
                            secondNode.isFrequent() &&
                            (ridDelta == null || ridDelta < MAX_RID_RANGE)
                            ) {
                        Refinement secondRef = pn.reverseAndChildren.get(new ReverseAndRefinement(secondAct.key.n.provider, secondAct.key.rid, pAct.key.rid));
                        Refinement nRef = new Refinement(secondRef.rid, ref.getOffset(), secondRef.input);

                        AndNode nln = createNextLevelNode(doc.m, doc.threadId, this, nRef, true);
                        if(nln != null) {
                            doc.addedNodes.add(nln);
                        }
                    }
                }
            }
            pn.lock.releaseReadLock();
        }
    }


    public boolean isExpandable(boolean checkFrequency) {
        if(checkFrequency && !isFrequent()) return false;
        return parents.size() < MAX_POS_NODES;
    }


    private static boolean checkRidRange(SortedMap<Refinement, Provider<? extends Node>> parents) {
        int maxRid = 0;
        for(Refinement ref: parents.keySet()) {
            if(ref.rid != null) {
                maxRid = Math.max(maxRid, ref.rid);
            }
        }
        return maxRid < MAX_RID_RANGE;
    }


    @Override
    boolean contains(Refinement ref) {
        // Check if this refinement is already present in this and-node.
        if(ref.rid == null || ref.rid >= 0) {
            boolean flag = false;
            lock.acquireReadLock();
            if(ref.rid == null || ref.rid > 0) {
                flag = parents.containsKey(ref);
            } else if(ref.rid == 0) {
                for(Refinement pRef: parents.keySet()) {
                    if((pRef.rid == null || pRef.rid <= 0) && pRef.input == ref.input) {
                        flag = true;
                        break;
                    }
                }
            }
            lock.releaseReadLock();
            return flag;
        }
        return false;
    }


    public static AndNode createNextLevelNode(Model m, int threadId, Node n, Refinement ref, boolean discoverPatterns) {
        Provider<AndNode> pnln = n.getAndChild(ref);
        if(pnln != null) {
            return discoverPatterns ? null : pnln.get();
        }

        if(n.contains(ref)) return null;

        SortedMap<Refinement, Provider<? extends Node>> parents = computeNextLevelParents(m, threadId, n, ref, discoverPatterns);

        AndNode nln = null;
        if (parents != null && (!discoverPatterns || checkRidRange(parents))) {
            // Locking needs to take place in a predefined order.
            TreeSet<? extends Provider<? extends Node>> parentsForLocking = new TreeSet(parents.values());
            for(Provider<? extends Node> pn: parentsForLocking) {
                pn.get().lock.acquireWriteLock();
            }

            if(n.andChildren == null || !n.andChildren.containsKey(ref)) {
                nln = new AndNode(m, n.level + 1, parents);
                nln.isBlocked = n.isBlocked || ref.input.get().isBlocked;
            }

            for(Provider<? extends Node> pn: parentsForLocking) {
                pn.get().lock.releaseWriteLock();
            }
        }
        return nln;
    }


    public static void addNextLevelActivation(Document doc, NodeActivation<AndNode> act, NodeActivation<AndNode> secondAct, Provider<AndNode> pnlp, InterprNode conflict) {
        // TODO: check if the activation already exists
        Key ak = act.key;
        InterprNode o = InterprNode.add(doc, true, ak.o, secondAct.key.o);
        if (o != null && (conflict == null || o.contains(conflict, false))) {
            AndNode nlp = pnlp.get();
            nlp.addActivation(
                    doc,
                    new Key(
                            nlp,
                            Range.mergeRange(ak.r, secondAct.key.r),
                            Utils.nullSafeMin(ak.rid, secondAct.key.rid),
                            o
                    ),
                    prepareInputActs(act, secondAct)
            );
        }
    }


    public static Collection<NodeActivation<?>> prepareInputActs(NodeActivation<?> firstAct, NodeActivation<?> secondAct) {
        List<NodeActivation<?>> inputActs = new ArrayList<>(2);
        inputActs.add(firstAct);
        inputActs.add(secondAct);
        return inputActs;
    }


    public static SortedMap<Refinement, Provider<? extends Node>> computeNextLevelParents(Model m, int threadId, Node pa, Refinement ref, boolean discoverPatterns) {
        Collection<Refinement> refinements = pa.collectNodeAndRefinements(ref);

        long v = visitedCounter++;
        SortedMap<Refinement, Provider<? extends Node>> parents = new TreeMap<>();

        for(Refinement pRef: refinements) {
            SortedSet<Refinement> childInputs = new TreeSet<>(refinements);
            childInputs.remove(pRef);
            try {
                if (!pRef.input.get().computeAndParents(m, threadId, pRef.getRelativePosition(), childInputs, parents, discoverPatterns, v)) {
                    return null;
                }
            } catch(ThreadState.RidOutOfRange e) {
                return null;
            }
        }

        return parents;
    }


    @Override
    Collection<Refinement> collectNodeAndRefinements(Refinement newRef) {
        List<Refinement> inputs = new ArrayList<>(parents.size() + 1);
        inputs.add(newRef);

        int numRidRefs = 0;
        for(Refinement ref: parents.keySet()) {
            if(ref.rid != null) numRidRefs++;
        }

        for(Refinement ref: parents.keySet()) {
            if(newRef.rid != null && newRef.rid != null && (newRef.rid < 0 || numRidRefs == 1)) {
                inputs.add(new Refinement(ref.getRelativePosition(), newRef.rid, ref.input));
            } else if(ref.rid != null && newRef.rid != null && ref.getOffset() < 0) {
                inputs.add(new Refinement(0, Math.min(-ref.getOffset(), newRef.getRelativePosition()) , ref.input));
            } else {
                inputs.add(ref);
            }
        }

        return inputs;
    }


    @Override
    public boolean isCovered(int threadId, Integer offset, long v) throws ThreadState.RidOutOfRange {
        for(Map.Entry<Refinement, Provider<? extends Node>> me: parents.entrySet()) {
            RidVisited nv = me.getValue().get().getThreadState(threadId, true).lookupVisited(Utils.nullSafeSub(offset, true, me.getKey().getOffset(), false));
            if(nv.outputNode == v) return true;
        }
        return false;
    }


    @Override
    public double computeSynapseWeightSum(Integer offset, INeuron n) {
        double sum = n.bias;
        for(Refinement ref: parents.keySet()) {
            Synapse s = ref.getSynapse(offset, n.provider);
            sum += Math.abs(s.w);
        }
        return sum;
    }


    @Override
    protected NodeActivation<AndNode> createActivation(Document doc, NodeActivation.Key ak, boolean isTrainingAct) {
        NodeActivation<AndNode> act = new NodeActivation<>(doc.activationIdCounter++, doc, ak);
        act.isTrainingAct = isTrainingAct;
        return act;
    }


    @Override
    public void deleteActivation(Document doc, NodeActivation act) {
    }


    @Override
    void remove(Model m) {
        super.remove(m);

        for(Map.Entry<Refinement, Provider<? extends Node>> me: parents.entrySet()) {
            Node pn = me.getValue().get();
            pn.lock.acquireWriteLock();
            pn.removeAndChild(me.getKey());
            pn.provider.setModified();
            pn.lock.releaseWriteLock();
        }
    }


    public String logicToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AND[");
        boolean first = true;
        for(Refinement ref: parents.keySet()) {
            if(!first) {
                sb.append(",");
            }
            first = false;
            sb.append(ref);
        }
        sb.append("]");
        return sb.toString();
    }

    public String weightsToString() {
        StringBuilder sb = new StringBuilder();
        sb.append(" - ");
        sb.append(" F:");
        sb.append(frequency);
        sb.append("  BW:");
        sb.append(Utils.round(weight));

        return sb.toString();
    }


    @Override
    public void write(DataOutput out) throws IOException {
        out.writeBoolean(false);
        out.writeUTF("A");
        super.write(out);

        out.writeInt(numberOfPositionsNotify);
        out.writeInt(frequencyNotify);

        out.writeDouble(weight);

        out.writeInt(parents.size());
        for(Map.Entry<Refinement, Provider<? extends Node>> me: parents.entrySet()) {
            me.getKey().write(out);
            out.writeInt(me.getValue().id);
        }
    }


    @Override
    public void readFields(DataInput in, Model m) throws IOException {
        super.readFields(in, m);

        numberOfPositionsNotify = in.readInt();
        frequencyNotify = in.readInt();

        weight = in.readDouble();

        int s = in.readInt();
        for(int i = 0; i < s; i++) {
            Refinement ref = Refinement.read(in, m);
            Provider<? extends Node> pn = m.lookupNodeProvider(in.readInt());
            parents.put(ref, pn);
        }
    }


    /**
     *
     */
    public static class Refinement implements Comparable<Refinement> {
        public static Refinement MIN = new Refinement(null, null);
        public static Refinement MAX = new Refinement(null, null);

        public Integer rid;
        public Provider<InputNode> input;

        private Refinement() {}

        public Refinement(Integer rid, Provider<InputNode> input) {
            this.rid = rid;
            this.input = input;
        }

        public Refinement(Integer rid, Integer offset, Provider<InputNode> input) {
            if(offset == null && rid != null) this.rid = 0;
            else if(offset == null || rid == null) this.rid = null;
            else this.rid = rid - offset;
            this.input = input;
        }


        public Integer getOffset() {
            return rid != null ? Math.min(0, rid) : null;
        }


        public Integer getRelativePosition() {
            return rid != null ? Math.max(0, rid) : null;
        }


        public Synapse getSynapse(Integer offset, Neuron n) {
            return input.get().getSynapse(Utils.nullSafeAdd(getRelativePosition(), false, offset, false), n);
        }


        public String toString() {
            return "(" + (rid != null ? rid + ":" : "") + input.get().logicToString() + ")";
        }


        public void write(DataOutput out) throws IOException {
            out.writeBoolean(rid != null);
            if(rid != null) out.writeInt(rid);
            out.writeInt(input.id);
        }


        public boolean readFields(DataInput in, Model m) throws IOException {
            if(in.readBoolean()) {
                rid = in.readInt();
            }
            input = m.lookupNodeProvider(in.readInt());
            return true;
        }


        public static Refinement read(DataInput in, Model m) throws IOException {
            Refinement k = new Refinement();
            k.readFields(in, m);
            return k;
        }


        @Override
        public int compareTo(Refinement ref) {
            if(this == MIN || ref == MAX) return -1;
            if(this == MAX || ref == MIN) return 1;

            int r = input.compareTo(ref.input);
            if(r != 0) return r;
            return Utils.compareInteger(rid, ref.rid);
        }
    }

}

package mage.player.ai;

import mage.game.Game;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.score.GameStateEvaluator3;
import mage.players.PlayerScript;

import java.util.Arrays;

/**
 * async version of MCTSNode. uses virtual visits like original AlphaZero paper.
 */
public class MCTSNode2 extends MCTSNode {
    public volatile boolean evaluationPending = false;

    /**
     * Per-thread scratch buffer for converting {@code stateVector} into the long[]
     * passed to the inference server. Reused across evaluations on the same thread
     * to keep young-gen GC pressure low; the inference future receives an
     * {@link Arrays#copyOf} of the live region so the pool can be safely reused
     * before the request completes. See madsbolaris/mage#2 (J2).
     */
    private static final ThreadLocal<long[]> INDEX_POOL = ThreadLocal.withInitial(() -> new long[4096]);

    public MCTSNode2(ComputerPlayerMCTS targetPlayer, Game game, ActionEncoder.ActionType actionType, PlayerScript prefixA, PlayerScript prefixB) {
        super(targetPlayer, game, actionType, prefixA, prefixB);
    }
    protected MCTSNode2(MCTSNode2 parent) {
        super(parent);
    }

    @Override
    protected MCTSNode2 createChild() {
        return new MCTSNode2(this);
    }

    /**
     * async call. wait for network result than finalize the node.
     */
    public void evaluate() {
        evaluationPending = true;
        ((ComputerPlayerMCTS2)basePlayer).pendingNodes.incrementAndGet();
        Game game = getGame();
        if(((ComputerPlayerMCTS2)basePlayer).offlineMode) {
            policy = null;
            if(actionType.equals(ActionEncoder.ActionType.PRIORITY)) {
                networkScore = GameStateEvaluator3.evaluateNormalized(targetPlayer, game);
            } else {
                if(parent != null) {
                    networkScore = getParent().networkScore;
                } else {
                    networkScore = 0;
                }
            }
            backpropagate(1 + networkScore, 0);
            ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
            // Signal both gates: the per-node awaitEvaluation lock and the global pending-slot lock.
            synchronized (this) {
                evaluationPending = false;
                notifyAll();
            }
            synchronized (basePlayer) {
                basePlayer.notifyAll();
            }
            return;
        }
        // Throttle concurrent in-flight evaluations. Use proper signaling instead of Thread.yield()
        // so worker threads can sleep until a slot opens up rather than burning CPU.
        synchronized (basePlayer) {
            while (((ComputerPlayerMCTS2) basePlayer).pendingNodes.get() > ComputerPlayerMCTS2.MAX_PENDING) {
                try {
                    basePlayer.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        long[] pool = INDEX_POOL.get();
        int n = stateVector.size();
        if (n > pool.length) {
            // Grow to next power of two so re-fetched indices fit without churn.
            int newCap = Integer.highestOneBit(Math.max(n, 1)) << 1;
            pool = new long[newCap];
            INDEX_POOL.set(pool);
        }
        int k = 0;
        for (int i : stateVector) {
            pool[k++] = i;
        }
        // Hand off a right-sized copy so the pooled buffer is safe to reuse on
        // the next evaluate() call before the inference future completes.
        long[] nnIndices = Arrays.copyOf(pool, k);

        ((ComputerPlayerMCTS2) basePlayer).nn.inferAsync(nnIndices)
                .thenAccept(out -> {
                    synchronized (basePlayer) {
                        // This runs on the HTTP executor thread when inference completes
                        switch (actionType) {
                            case PRIORITY:
                                if(basePlayer.noPolicyPriority) break;
                                if (targetPlayer.equals(playerId)) {
                                    policy = out.policy_player;
                                } else {
                                    if (!basePlayer.noPolicyOpponent) {
                                        policy = out.policy_opponent;
                                    }
                                }
                                break;
                            case CHOOSE_TARGET:
                                if(basePlayer.noPolicyTarget) break;
                                if (targetPlayer.equals(playerId) || !basePlayer.noPolicyOpponent) {
                                    policy = out.policy_target;
                                }
                                break;
                            case CHOOSE_USE:
                                if(basePlayer.noPolicyUse) break;
                                if (targetPlayer.equals(playerId) || !basePlayer.noPolicyOpponent) {
                                    policy = out.policy_binary;
                                }
                                break;
                            default:
                                policy = null;
                        }

                        networkScore = out.value;
                        backpropagate(1 + networkScore, 0);
                        setPriors();
                        ((ComputerPlayerMCTS2) basePlayer).pendingNodes.decrementAndGet();
                        // Wake any thread waiting on the pending-slot gate.
                        basePlayer.notifyAll();
                    }
                    // Wake any thread blocked in awaitEvaluation() for this node.
                    synchronized (this) {
                        evaluationPending = false;
                        notifyAll();
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    logger.error("REMOTE EVAL FAILURE");
                    // Still backprop something on failure so tree doesn't get stuck
                    backpropagate(0, 0);
                    ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
                    synchronized (basePlayer) {
                        basePlayer.notifyAll();
                    }
                    synchronized (this) {
                        evaluationPending = false;
                        notifyAll();
                    }
                    throw new RuntimeException("REMOTE EVAL FAILURE");
                });

    }
    public synchronized void awaitEvaluation() {
        while (evaluationPending) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

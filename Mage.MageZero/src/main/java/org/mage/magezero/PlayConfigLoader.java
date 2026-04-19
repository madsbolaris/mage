package org.mage.magezero;

import mage.player.ai.config.MCTSDefaults;


public class PlayConfigLoader {

    /** Called reflectively from MCTSDefaults when -Dmagezero.play.config is set. */
    public static MCTSDefaults load(String path) {
        Config.load(path);
        Config.PlayerConfig p = Config.INSTANCE.playerA;

        MCTSDefaults d = new MCTSDefaults();
        d.searchBudget      = p.mcts.searchBudget;
        d.searchTimeout     = p.mcts.timeoutMs / 1000.0;
        d.priorTemp         = p.priors.priorTemperature;
        d.noPolicyPriority  = !p.priors.priority;
        d.noPolicyTarget    = !p.priors.target;
        d.noPolicyUse       = !p.priors.binary;
        d.noPolicyOpponent  = !p.priors.opponent;
        d.noNoise           = !p.noise.enabled;
        return d;
    }
}
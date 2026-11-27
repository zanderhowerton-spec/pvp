package dev.npclife;

import java.util.Locale;

/** Every autonomous action. Actions in different channels can run at the same time. */
public enum ActionType {
    //            prio  channels                                         prob cdMin cdMax durMin durMax
    LOOK_AROUND(1, new Channel[]{Channel.HEAD},                          60,  3,  8,  1,   3),
    WATCH      (2, new Channel[]{Channel.HEAD},                          40,  4, 10,  2,   6),
    TURN_AROUND(1, new Channel[]{Channel.HEAD},                           8, 10, 30,  1,   2),
    WALK       (2, new Channel[]{Channel.MOVE},                          40,  2, 10,  6,  25),
    APPROACH   (3, new Channel[]{Channel.MOVE},                           5, 20, 60,  6,  15),
    FLEE       (5, new Channel[]{Channel.MOVE},                           0,  6, 20,  5,  12),
    IDLE       (1, new Channel[]{Channel.MOVE},                          25,  5, 15,  2,   8),
    JUMP       (1, new Channel[]{Channel.JUMP},                          15,  4, 12, 0.5,  2),
    SPRINT     (1, new Channel[]{Channel.SPRINT},                        10, 10, 30,  2,   6),
    SNEAK      (1, new Channel[]{Channel.SNEAK},                          8, 15, 40,  2,   6),
    SNEAK_SPAM (1, new Channel[]{Channel.SNEAK},                          3, 30, 90,  1,   3),
    INTERACT   (2, new Channel[]{Channel.MOVE, Channel.HEAD, Channel.HAND}, 5, 30, 90,  3,   6),
    SWAP_ITEM  (1, new Channel[]{Channel.HAND},                           8, 15, 40,  0,   0),
    SWING      (1, new Channel[]{Channel.HAND},                           6, 20, 60,  0,   0),
    GREET      (3, new Channel[]{Channel.HEAD, Channel.SNEAK},            0,  8, 20, 1.5, 3.5),
    BROWSE     (2, new Channel[]{Channel.MOVE},                           0,  4, 12,  8,  22);

    public enum Channel { HEAD, MOVE, JUMP, SPRINT, SNEAK, HAND }

    final int priority;
    final Channel[] channels;
    final double prob, cdMin, cdMax, durMin, durMax;

    ActionType(int priority, Channel[] channels, double prob, double cdMin, double cdMax, double durMin, double durMax) {
        this.priority = priority; this.channels = channels;
        this.prob = prob; this.cdMin = cdMin; this.cdMax = cdMax; this.durMin = durMin; this.durMax = durMax;
    }

    ActionConfig defaults() {
        ActionConfig c = new ActionConfig();
        c.probability = prob; c.cdMin = cdMin; c.cdMax = cdMax; c.durMin = durMin; c.durMax = durMax;
        return c;
    }

    static ActionType parse(String s) {
        String k = s.toUpperCase(Locale.ROOT).replace('-', '_');
        switch (k) {
            case "LOOK": case "LOOKAROUND": return LOOK_AROUND;
            case "TURN": return TURN_AROUND;
            case "STAND": case "STILL": return IDLE;
            case "SPAM": return SNEAK_SPAM;
            case "SWAP": case "ITEM": return SWAP_ITEM;
            case "ARM": return SWING;
            case "FOLLOW": return APPROACH;
            default:
                try { return valueOf(k); } catch (IllegalArgumentException ex) { return null; }
        }
    }
}

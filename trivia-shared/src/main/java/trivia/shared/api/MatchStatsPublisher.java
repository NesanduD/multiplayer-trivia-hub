package trivia.shared.api;

import trivia.shared.dto.MatchResult;

/**
 * Contract between the game engine and the HTTP stats service.
 */
public interface MatchStatsPublisher {
    void publish(MatchResult result);
}

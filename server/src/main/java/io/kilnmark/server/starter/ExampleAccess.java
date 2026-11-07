package io.kilnmark.server.starter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.schedules.ScheduleStore;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Whether a run was asked for by one of the Starter Pack's example accounts (ADR 0051), as a run
 * names who asked for it: a person by the name their sign-in goes by; a token as
 * {@code token <id> (<name>)}, by its id alone, since names are chosen freely and need not be
 * unique; a schedule as {@code schedule <id> (set by ...)}, by the schedule's id and who set it.
 * On production such a run is cancelled, at start-up and by a worker before it starts.
 */
@ApplicationScoped
public class ExampleAccess {

    /** How a scheduled run names its schedule ({@code ScheduleService.requestedBy}). */
    private static final Pattern SCHEDULED = Pattern.compile("^schedule (\\d{1,18}) \\(set by (.*)\\)$", Pattern.DOTALL);

    private final ApiTokens tokens;
    private final ScheduleStore schedules;

    public ExampleAccess(ApiTokens tokens, ScheduleStore schedules) {
        this.tokens = tokens;
        this.schedules = schedules;
    }

    /** Whether {@code who}, as a run names who asked for it, is or acts for an example account. */
    public boolean actsForExample(String who) {
        if (who == null) {
            return false;
        }
        Matcher s = SCHEDULED.matcher(who);
        if (s.matches()) {
            // by the schedule itself: the token that set it (by id), or the person
            return ExampleAccounts.isExample(s.group(2)) || schedules.find(Long.parseLong(s.group(1)))
                .map(x -> x.setByToken() != null ? tokens.madeByExample(x.setByToken())
                    : ExampleAccounts.isExample(x.updatedBy()))
                .orElse(false);
        }
        if (ExampleAccounts.isExample(who)) {
            return true;
        }
        Matcher t = ApiTokens.AS_CALLER.matcher(who);
        return t.find() && tokens.madeByExample(Long.parseLong(t.group(1)));
    }
}

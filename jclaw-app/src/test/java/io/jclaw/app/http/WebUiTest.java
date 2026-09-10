package io.jclaw.app.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyboard contract of the browser UI, and the two places its send path used to lose work.
 *
 * <p>These are assertions about the page source, which is a weaker thing than a browser test and
 * worth naming as such: they pin the decisions, not the rendering. The behaviour itself was
 * verified by running the real served page in headless Chrome and dispatching actual
 * {@code KeyboardEvent}s for every modifier combination — {@code scripts/web-ui-keys.sh} does
 * exactly that and is how a change here should be re-checked. It is not part of {@code mvn test}
 * because it needs a Chrome on the machine, which CI does not have.
 *
 * <p>What is being defended: the page is one embedded constant with no build step, so nothing else
 * in the project would notice if a key binding regressed. It shipped advertising {@code Ctrl+Enter}
 * — a chord that is not how a Mac sends anything — while binding no plain key at all, so a Mac
 * user pressed what the page told them to press and the page did nothing.
 */
class WebUiTest {

    private static final String PAGE = WebUi.INDEX;

    @Test
    @DisplayName("Enter sends on its own, so the send chord is not a platform question")
    void plainEnterIsBound() {
        assertTrue(PAGE.contains("e.key !== \"Enter\""),
                "the keydown handler must key off Enter itself");
        assertFalse(PAGE.contains("e.key === \"Enter\" && (e.ctrlKey || e.metaKey)"),
                "requiring a modifier is the bug: Ctrl is not the macOS send modifier");
    }

    @Test
    @DisplayName("Shift+Enter is excluded, which is what leaves room for a newline")
    void shiftEnterMakesANewline() {
        assertTrue(PAGE.contains("if (e.shiftKey) return;"),
                "Shift+Enter must fall through to the default newline");
    }

    @Test
    @DisplayName("both the old chord and the macOS one still send")
    void bothChordsStillSend() {
        // Neither sets shiftKey, so binding Enter and excluding Shift covers Ctrl+Enter and Cmd+Enter
        // without naming either. The placeholder must not advertise a chord as the only way in.
        String placeholder = PAGE.substring(PAGE.indexOf("placeholder=\"Ask the agent"));
        placeholder = placeholder.substring(0, placeholder.indexOf('"', placeholder.indexOf('"') + 1) + 1);
        assertTrue(placeholder.contains("Enter to send"),
                "the placeholder must describe the binding that actually exists, was: " + placeholder);
        assertFalse(placeholder.contains("Ctrl+Enter"),
                "telling a Mac user to press Ctrl+Enter is the original complaint, was: " + placeholder);
    }

    @Test
    @DisplayName("an IME committing a candidate does not submit the message")
    void composingEnterIsIgnored() {
        int composing = PAGE.indexOf("e.isComposing");
        int legacy = PAGE.indexOf("e.keyCode === 229");
        int prevent = PAGE.indexOf("e.preventDefault()");
        assertTrue(composing > 0, "isComposing must be checked once plain Enter is bound");
        assertTrue(legacy > 0, "Safari and older Chrome report composition as keyCode 229 only");
        assertTrue(composing < prevent && legacy < prevent,
                "both composition checks must precede preventDefault, or Enter eats the candidate");
    }

    @Test
    @DisplayName("the keystroke is swallowed, so sending leaves no newline behind")
    void sendingPreventsTheDefault() {
        assertTrue(PAGE.contains("e.preventDefault()"),
                "without this the same keystroke drops a newline into the just-cleared box");
    }

    @Test
    @DisplayName("the typed text is cleared only once the turn is durable")
    void textSurvivesARefusedTurn() {
        int post = PAGE.indexOf("await api(\"/threads/\" + encodeURIComponent(thread()) + \"/turns\"");
        int clear = PAGE.indexOf("$(\"text\").value = \"\";");
        assertTrue(post > 0 && clear > 0, "both the post and the clear must be present");
        assertTrue(clear > post,
                "clearing before the post loses what the user typed whenever the turn is refused");
    }

    @Test
    @DisplayName("a non-2xx answer is a refusal, not a result")
    void refusalsAreNotReadAsSuccess() {
        assertTrue(PAGE.contains("if (!r.ok) throw"),
                "returning the body for any status but 401 made a 409 busy thread read as success");
    }

    @Test
    @DisplayName("one Enter is one turn, however fast it is pressed twice")
    void doubleEnterQueuesOneTurn() {
        assertTrue(PAGE.contains("if (sending) return;"),
                "a second submission on a live thread is refused, which reads as a lost message");
    }
}

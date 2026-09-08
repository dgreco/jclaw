package io.jclaw.contracts.turn;

/**
 * Checkpoint projection identity for a persisted loop state snapshot.
 */
public record CheckpointId(String value) implements Ident {

    public CheckpointId {
        value = Ident.validate(value, "CheckpointId");
    }

    /** Mints a fresh, unique CheckpointId. */
    public static CheckpointId fresh() {
        return new CheckpointId(Ident.fresh("ckpt"));
    }

    @Override
    public String toString() {
        return value;
    }
}

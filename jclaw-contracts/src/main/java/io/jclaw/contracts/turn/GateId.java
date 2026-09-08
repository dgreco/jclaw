package io.jclaw.contracts.turn;

/**
 * Identity of an approval or auth gate raised during a run.
 */
public record GateId(String value) implements Ident {

    public GateId {
        value = Ident.validate(value, "GateId");
    }

    /** Mints a fresh, unique GateId. */
    public static GateId fresh() {
        return new GateId(Ident.fresh("gate"));
    }

    @Override
    public String toString() {
        return value;
    }
}

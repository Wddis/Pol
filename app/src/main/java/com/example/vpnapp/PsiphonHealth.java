package com.example.vpnapp;

/** Состояние транспорта и проверенного HTTPS, используется из одного рабочего потока. */
final class PsiphonHealth {
    enum Change { NONE, READY, RESTORED, LOST }

    private int epoch;
    private int failures;
    private boolean transportUp;
    private boolean verified;
    private boolean connectedBefore;

    void reset() {
        epoch++;
        failures = 0;
        transportUp = false;
        verified = false;
        connectedBefore = false;
    }

    boolean transportUp() {
        return transportUp;
    }

    boolean connectedBefore() {
        return connectedBefore;
    }

    int epoch() {
        return epoch;
    }

    Change transport(boolean up) {
        if (up == transportUp) return Change.NONE;
        transportUp = up;
        epoch++;
        failures = 0;
        if (up || !verified) return Change.NONE;
        verified = false;
        return Change.LOST;
    }

    Change probe(int ticket, boolean success) {
        if (ticket != epoch || !transportUp) return Change.NONE;
        if (success) {
            failures = 0;
            if (verified) return Change.NONE;
            verified = true;
            Change event = connectedBefore ? Change.RESTORED : Change.READY;
            connectedBefore = true;
            return event;
        }
        if (verified && ++failures >= 2) {
            verified = false;
            return Change.LOST;
        }
        return Change.NONE;
    }
}

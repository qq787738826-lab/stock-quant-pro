package com.stockquant.server.agent.marketfacts;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregates written permission and technical evidence without allowing
 * either dimension to silently upgrade the other.
 */
public final class TushareF1EntryQualification {

    private final TushareWrittenPermissionQualification writtenPermission;
    private final TushareTechnicalQualification technicalQualification;
    private final GateStatus writtenPermissionGate;
    private final GateStatus technicalEvidenceGate;
    private final EntryReadiness entryReadiness;
    private final Set<EntryBlocker> activeBlockers;

    private TushareF1EntryQualification(
            TushareWrittenPermissionQualification writtenPermission,
            TushareTechnicalQualification technicalQualification,
            GateStatus writtenPermissionGate,
            GateStatus technicalEvidenceGate,
            EntryReadiness entryReadiness,
            Set<EntryBlocker> activeBlockers
    ) {
        this.writtenPermission = writtenPermission;
        this.technicalQualification = technicalQualification;
        this.writtenPermissionGate = writtenPermissionGate;
        this.technicalEvidenceGate = technicalEvidenceGate;
        this.entryReadiness = entryReadiness;
        this.activeBlockers = Set.copyOf(activeBlockers);
        validateInvariants();
    }

    public static TushareF1EntryQualification assess(
            TushareWrittenPermissionQualification writtenPermission,
            TushareTechnicalQualification technicalQualification
    ) {
        Objects.requireNonNull(writtenPermission, "writtenPermission");
        Objects.requireNonNull(
                technicalQualification, "technicalQualification");
        GateStatus writtenGate =
                writtenPermission.personalResearchPermissionComplete()
                        ? GateStatus.PASS : GateStatus.BLOCKED;
        GateStatus technicalGate =
                technicalQualification.fullTechnicalContractReady()
                        ? GateStatus.PASS : GateStatus.BLOCKED;
        Set<EntryBlocker> blockers =
                EnumSet.noneOf(EntryBlocker.class);
        if (writtenGate == GateStatus.BLOCKED) {
            blockers.add(EntryBlocker.BLOCKED_WRITTEN_PERMISSION);
        }
        if (technicalGate == GateStatus.BLOCKED) {
            blockers.add(EntryBlocker.BLOCKED_TECHNICAL_EVIDENCE);
        }
        EntryReadiness readiness;
        if (blockers.isEmpty()) {
            readiness = EntryReadiness.READY;
        } else if (blockers.equals(
                Set.of(EntryBlocker.BLOCKED_WRITTEN_PERMISSION))) {
            readiness =
                    EntryReadiness.BLOCKED_WRITTEN_PERMISSION;
        } else if (blockers.equals(
                Set.of(EntryBlocker.BLOCKED_TECHNICAL_EVIDENCE))) {
            readiness =
                    EntryReadiness.BLOCKED_TECHNICAL_EVIDENCE;
        } else {
            readiness = EntryReadiness.BLOCKED_MULTIPLE;
        }
        return new TushareF1EntryQualification(
                writtenPermission,
                technicalQualification,
                writtenGate,
                technicalGate,
                readiness,
                blockers);
    }

    private void validateInvariants() {
        boolean writtenReady =
                writtenPermission.personalResearchPermissionComplete();
        boolean technicalReady =
                technicalQualification.fullTechnicalContractReady();
        if ((writtenPermissionGate == GateStatus.PASS) != writtenReady
                || (technicalEvidenceGate == GateStatus.PASS)
                != technicalReady) {
            throw new IllegalArgumentException(
                    "F1 gate contradicts source qualification");
        }
        Set<EntryBlocker> expected =
                EnumSet.noneOf(EntryBlocker.class);
        if (!writtenReady) {
            expected.add(EntryBlocker.BLOCKED_WRITTEN_PERMISSION);
        }
        if (!technicalReady) {
            expected.add(EntryBlocker.BLOCKED_TECHNICAL_EVIDENCE);
        }
        if (!activeBlockers.equals(expected)) {
            throw new IllegalArgumentException(
                    "F1 blocker set contradicts gates");
        }
        if ((entryReadiness == EntryReadiness.READY)
                != activeBlockers.isEmpty()) {
            throw new IllegalArgumentException(
                    "F1 readiness contradicts blockers");
        }
    }

    public TushareWrittenPermissionQualification writtenPermission() {
        return writtenPermission;
    }

    public TushareTechnicalQualification technicalQualification() {
        return technicalQualification;
    }

    public GateStatus writtenPermissionGate() {
        return writtenPermissionGate;
    }

    public GateStatus technicalEvidenceGate() {
        return technicalEvidenceGate;
    }

    public EntryReadiness entryReadiness() {
        return entryReadiness;
    }

    public Set<EntryBlocker> activeBlockers() {
        return activeBlockers;
    }

    public boolean fullF1EntryReady() {
        return entryReadiness == EntryReadiness.READY;
    }

    public enum GateStatus {
        PASS,
        BLOCKED
    }

    public enum EntryReadiness {
        READY,
        BLOCKED_WRITTEN_PERMISSION,
        BLOCKED_TECHNICAL_EVIDENCE,
        BLOCKED_MULTIPLE
    }

    public enum EntryBlocker {
        BLOCKED_WRITTEN_PERMISSION,
        BLOCKED_TECHNICAL_EVIDENCE
    }
}

package org.webtrade.strategy1.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/** What a soldier does: fights (with a blade or a bow), finds whom to fight, and keeps to its orders. */
final class SoldierGoals {

    private SoldierGoals() {
    }

    /** How far a soldier looks for enemies, by its orders. */
    static double aggro(SoldierEntity s) {
        // (bowmen look out as far as they shoot)
        if (s.unit().ranged && s.mode != SoldierEntity.Mode.MOVE) return Math.max(base(s), s.reach());
        return base(s);
    }

    private static double base(SoldierEntity s) {
        return switch (s.mode) {
            case FOLLOW -> 16;
            case HOLD -> 16;
            case MOVE -> 8;
            case GUARD -> 30;
            case RAID -> 20;
            case POSTED -> Math.max(30, s.reach());
            default -> 10;
        };
    }

    /** How far from its post it chases an enemy (0: no limit). */
    static double leash(SoldierEntity s) {
        return switch (s.mode) {
            case HOLD -> s.unit().ranged ? s.reach() + 4 : 22;
            case GUARD -> 44;
            default -> 0;
        };
    }

    /** Sword in hand: as the game's own melee, for all but archers. */
    static final class Melee extends MeleeAttackGoal {
        private final SoldierEntity s;

        Melee(SoldierEntity s) {
            super(s, 1.25, true);
            this.s = s;
        }

        private boolean may() {
            return !s.unit().ranged && !s.unit().heals() && !s.isPassenger() && s.mode != SoldierEntity.Mode.POSTED;
        }

        @Override
        public boolean canUse() {
            return may() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return may() && super.canContinueToUse();
        }
    }

    /** Bow in hand: keeps at a distance and looses arrows. */
    static final class Shoot extends Goal {
        private final SoldierEntity s;

        Shoot(SoldierEntity s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return s.unit().ranged && s.getTarget() != null && s.getTarget().isAlive();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            s.setAggressive(false);
        }

        @Override
        public void tick() {
            LivingEntity t = s.getTarget();
            if (t == null) return;
            s.setAggressive(true);
            s.getLookControl().setLookAt(t, 30, 30);
            double d = s.distanceTo(t);
            boolean sees = s.getSensing().hasLineOfSight(t);
            if (s.mode == SoldierEntity.Mode.POSTED || s.isPassenger()) {
                // from a tower, a wall or a deck: no stepping about
                if (sees && d < s.reach()) s.shoot(t);
                return;
            }
            if (d > s.reach() || !sees) {
                s.walkTo(t.position(), 1.1);
            } else {
                s.getNavigation().stop();
                if (d < 5) {
                    // too close: a step back
                    Vec3 away = s.position().subtract(t.position()).normalize().scale(4).add(s.position());
                    s.getNavigation().moveTo(away.x, away.y, away.z, 1.2);
                }
                s.shoot(t);
            }
        }
    }

    /** Keeps to its orders while there's nobody to fight. */
    static final class Orders extends Goal {
        private final SoldierEntity s;

        Orders(SoldierEntity s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return (s.getTarget() == null || s.mode == SoldierEntity.Mode.BREAK) && !s.isPassenger() && s.mode != SoldierEntity.Mode.POSTED;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            switch (s.mode) {
                case POST -> {
                    Vec3 at = org.webtrade.strategy1.game.StrategyManager.structurePoint(s.structure, s.position());
                    if (at == null) {
                        s.order(SoldierEntity.Mode.HOLD, s.position());
                        return;
                    }
                    if (s.position().distanceTo(at) > 3.5) s.walkTo(at, 1.2);
                    else if (!org.webtrade.strategy1.game.StrategyManager.man(s, s.structure)) s.order(SoldierEntity.Mode.HOLD, s.position());
                }
                case BOARD -> {
                    if (!(s.level().getEntity(s.ship) instanceof ShipEntity ship) || !ship.isAlive()) {
                        s.order(SoldierEntity.Mode.HOLD, s.position());
                        return;
                    }
                    if (s.distanceTo(ship) > 4.5) s.walkTo(ship.position(), 1.2);
                    else if (!ship.board(s)) s.order(SoldierEntity.Mode.HOLD, s.position());
                }
                case BREAK -> {
                    Vec3 at = org.webtrade.strategy1.game.StrategyManager.structurePoint(s.structure, s.position());
                    if (at == null) {
                        s.order(SoldierEntity.Mode.HOLD, s.position());
                        return;
                    }
                    if (s.position().distanceTo(at) > 2.8) s.walkTo(at, 1.2);
                    else {
                        s.getNavigation().stop();
                        s.getLookControl().setLookAt(at.x, at.y + 1, at.z);
                        s.strike();
                    }
                }
                case POSTED -> {
                }
                case FOLLOW -> {
                    ServerPlayer p = s.commanderPlayer();
                    if (p == null || p.level() != s.level()) {
                        s.mode = SoldierEntity.Mode.HOLD;
                        s.anchor = s.position();
                        return;
                    }
                    double d = s.distanceTo(p);
                    if (d > 72 && p.onGround()) {
                        // fallen far behind: catches up (as tamed animals do)
                        Vec3 at = s.slot(p.position(), p.getYRot());
                        if (s.level() instanceof net.minecraft.server.level.ServerLevel sl) at = SoldierEntity.surface(sl, at.x, at.z);
                        if (Math.abs(at.y - p.getY()) > 6) at = p.position();
                        s.snapTo(at.x, at.y, at.z, s.getYRot(), s.getXRot());
                        s.getNavigation().stop();
                        return;
                    }
                    Vec3 slot = s.slot(p.position(), p.getYRot());
                    if (s.position().distanceTo(slot) > 2.5) s.walkTo(slot, d > 12 ? 1.35 : 1.0);
                    else s.getNavigation().stop();
                }
                case HOLD -> {
                    if (s.position().distanceTo(s.anchor) > 2) s.walkTo(s.anchor, 1.0);
                }
                case GUARD -> {
                    Vec3 slot = s.slot(s.anchor, 0);
                    if (s.position().distanceTo(slot) > 2) s.walkTo(slot, 1.0);
                }
                case MOVE -> {
                    Vec3 slot = s.slot(s.anchor, 0);
                    if (s.position().distanceTo(slot) > 2) s.walkTo(slot, 1.2);
                    else {
                        s.mode = SoldierEntity.Mode.HOLD;
                        s.anchor = s.position();
                    }
                }
                case RAID -> {
                    Vec3 slot = s.slot(s.anchor, 0);
                    if (s.position().distanceTo(slot) > 2) s.walkTo(slot, 1.1);
                }
            }
        }
    }

    /** Picks the nearest enemy in reach (or whoever struck it), and lets go when it strays too far from its post. */
    static final class FindEnemy extends Goal {
        private final SoldierEntity s;
        private int wait;

        FindEnemy(SoldierEntity s) {
            this.s = s;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            // (a healer seeks nobody out)
            return !s.unit().heals();
        }

        @Override
        public boolean canContinueToUse() {
            return true;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = s.getTarget();
            double leash = leash(s);
            if (t != null) {
                boolean gone = !t.isAlive() || !s.hostileTo(t) || s.distanceTo(t) > 48;
                if (!gone && leash > 0 && t.position().distanceTo(s.anchor) > leash) gone = true;
                if (!gone && s.mode == SoldierEntity.Mode.FOLLOW) {
                    ServerPlayer p = s.commanderPlayer();
                    if (p != null && p.distanceTo(t) > 28) gone = true;
                }
                if (gone) {
                    s.setTarget(null);
                    t = null;
                }
            }
            if (--wait > 0) return;
            wait = 10;
            if (s.mode == SoldierEntity.Mode.BREAK && t == null) return;
            LivingEntity hurtBy = s.getLastHurtByMob();
            if (hurtBy != null && hurtBy.isAlive() && s.hostileTo(hurtBy) && s.distanceTo(hurtBy) < 32
                    && (leash == 0 || hurtBy.position().distanceTo(s.anchor) < leash)) {
                if (t == null || s.distanceTo(hurtBy) < s.distanceTo(t) - 3) s.setTarget(hurtBy);
                return;
            }
            if (t != null) return;
            double r = aggro(s);
            List<LivingEntity> near = s.level().getEntitiesOfClass(LivingEntity.class, s.getBoundingBox().inflate(r, s.mode == SoldierEntity.Mode.POSTED || s.isPassenger() ? 24 : 8, r),
                    e -> s.hostileTo(e) && (leash == 0 || e.position().distanceTo(s.anchor) < leash));
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (LivingEntity e : near) {
                double d = s.distanceToSqr(e);
                if (d < bestD && s.getSensing().hasLineOfSight(e)) {
                    bestD = d;
                    best = e;
                }
            }
            if (best != null) s.setTarget(best);
        }
    }
}

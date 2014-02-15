/*
 * Copyright 2014 Cazcade Limited (http://cazcade.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kontrol.common.group.ext

import kontrol.api.MachineGroup.Recheck.*
import kontrol.api.MachineGroupState.*
import kontrol.api.GroupAction.*
import kontrol.api.Action.*
import kontrol.api.MachineState.*
import kontrol.api.Controller
import kontrol.api.MachineGroup
import kontrol.api.PostmortemStore
import kontrol.api.Machine

/**
 * @todo document.
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */


public fun MachineGroup.allowDefaultTransitions() {

    this allowMachine (OK to STOPPED);
    this allowMachine (OK to BROKEN);
    this allowMachine (OK to STALE);
    this allowMachine (STOPPED to DEAD);
    this allowMachine (STOPPED to OK);
    this allowMachine (STOPPED to BROKEN);
    this allowMachine (BROKEN to STALE);
    this allowMachine (BROKEN to OK);
    this allowMachine (BROKEN to DEAD);
    this allowMachine (BROKEN to FAILED);
    this allowMachine (STALE to OK);
    this allowMachine (OK to OVERLOADED);
    this allowMachine (OVERLOADED to OK);
    this allowMachine (OVERLOADED to BROKEN);
    this allowMachine (OVERLOADED to DEAD);
    this allowMachine (OVERLOADED to FAILED);
    this allowMachine (OVERLOADED to STALE);
    this allowMachine (STALE to UPGRADE_FAILED);
    this allowMachine (UPGRADE_FAILED to OK);
    this allowMachine (UPGRADE_FAILED to BROKEN);
    this allowMachine (UPGRADE_FAILED to DEAD);
    this allowMachine (UPGRADE_FAILED to FAILED);
    this allowMachine (DEAD to FAILED);
    this allowMachine (DEAD to OK);
    this allowMachine (OK to FAILED);


    this allow (QUIET to BUSY);
    this allow (QUIET to NORMAL);
    this allow (QUIET to GROUP_BROKEN);
    this allow (BUSY to NORMAL);
    this allow (BUSY to QUIET);
    this allow (BUSY to  GROUP_BROKEN);
    this allow (NORMAL to GROUP_BROKEN);
    this allow (NORMAL to QUIET);
    this allow (NORMAL to BUSY);
    this allow (GROUP_BROKEN to QUIET);
    this allow (GROUP_BROKEN to BUSY);
    this allow (GROUP_BROKEN to NORMAL);
}


public fun MachineGroup.applyDefaultPolicies(controller: Controller, postmortemStore: PostmortemStore, upgradeAction: (Machine, MachineGroup) -> Unit = { m, g -> m.disable();g.rebuild(m);m.enable() }, downgradeAction: (Machine, MachineGroup) -> Unit = { m, g -> }) {

    this whenMachine BROKEN recheck THEN tell controller takeActions listOf(FIX);
    this whenMachine OK recheck THEN tell controller takeAction FAILBACK;
    this whenMachine DEAD recheck THEN tell controller takeActions listOf(REBUILD) ;
    this whenMachine STALE recheck THEN tell controller takeActions listOf(STAGGER, UPGRADE);
    this whenMachine UPGRADE_FAILED recheck THEN tell controller takeActions listOf(STAGGER, DOWNGRADE);
    this whenMachine FAILED recheck THEN tell controller takeActions listOf(DESTROY_MACHINE);
    this whenGroup BUSY recheck THEN use controller takeAction EXPAND;
    this whenGroup QUIET recheck THEN use controller takeAction CONTRACT;
    this whenGroup GROUP_BROKEN recheck THEN use controller  takeActions listOf(EMERGENCY_FIX);

    controller will { this.failover(it);upgradeAction(it, this);java.lang.String() } takeAction UPGRADE inGroup this;
    controller will { this.failover(it);downgradeAction(it, this);java.lang.String() } takeAction DOWNGRADE inGroup this;
    controller will { Thread.sleep((10 * 60 * 1000 * Math.random()).toLong()); java.lang.String() } takeAction STAGGER inGroup this;
    controller will { this.failback(it); java.lang.String() } takeAction FAILBACK inGroup this;

    controller will {
        this.failover(it);
        postmortemStore.addAll(this.postmortem(it));
        this.rebuild(it);
        downgradeAction(it, this);
        this.clearState(it);
        this.configure(it)
        java.lang.String()
    } takeAction REBUILD inGroup this;

    controller will { this.failover(it);postmortemStore.addAll(this.postmortem(it));downgradeAction(it, this);this.restart(it);this.clearState(it); this.configure(it);java.lang.String() } takeAction FIX inGroup this;

    controller will { this.failover(it); this.expand();it.disable(); postmortemStore.addAll(this.postmortem(it));this.destroy(it); it.enable();java.lang.String() } takeAction DESTROY_MACHINE inGroup this;
    controller use { downgradeAction(this.configure(this.expand()), this); java.lang.String() } to EXPAND  IF { this.machines().size < this.hardMax }  group this;

    controller use { this.contract(); this.configure();java.lang.String() } to CONTRACT IF { this.workingSize() > this.min } group this;
    controller use {
        if (this.machines().size < this.hardMax) {
            this.expand()
        }
        it.brokenMachines().sortBy { it.id() }.forEach { this.rebuild(it);downgradeAction(it, this);this.clearState(it); this.configure(it) }; this.configure();java.lang.String()
    } to EMERGENCY_FIX group this;
}

public fun MachineGroup.applyDefaultRules(timeFactor: Int = 60) {
    this memberIs STALE ifStateIn listOf(OK, STALE, OVERLOADED) andTest { this.other(it) != null } after  timeFactor * 15  seconds "upgrade"
    this memberIs BROKEN ifStateIn listOf(BROKEN, null, STOPPED) after timeFactor * 5 seconds "bad-now-broken"
    this memberIs UPGRADE_FAILED ifStateIn listOf(STALE) after timeFactor * 30 seconds "stale-now-failed"
    this memberIs DEAD ifStateIn listOf(OVERLOADED, DEAD, BROKEN, STOPPED) after timeFactor * 5 seconds "escalate-broken-to-dead"
    val escalateDuration: Long = 60L * timeFactor * 1000L
    this memberIs DEAD ifStateIn listOf(DEAD, BROKEN, STOPPED) andTest { it.fsm.history.percentageInWindow(listOf(BROKEN, STOPPED, DEAD), (escalateDuration / 2)..escalateDuration) > 30.0 } after timeFactor seconds "flap-now-escalate-to-dead"
    this memberIs FAILED ifStateIn listOf(DEAD, FAILED) after timeFactor * 10 seconds "dead-now-failed"
    this memberIs FAILED ifStateIn listOf(FAILED, DEAD) andTest { it.fsm.history.percentageInWindow(listOf(DEAD, FAILED), (escalateDuration / 2)..escalateDuration) > 40.0 } after timeFactor seconds "flap-now-escalate-to-failed"
}

public fun MachineGroup.addMachineOverloadRules(vararg rules: Pair<String, Double>, timeFactor: Int = 60) {

    this memberIs OK ifStateIn listOf(OVERLOADED) andTest { machine -> rules.all { machine[it.first]?.D()?:(it.second + 1) < it.second } } after timeFactor / 2 seconds "machine-ok-load"

    this memberIs OVERLOADED ifStateIn listOf(OK, null) andTest { machine -> rules.any { machine[it.first]?.D()?:(it.second - 1) > it.second } } after timeFactor * 4 seconds "machine-overloaded"
}

public fun MachineGroup.addMachineBrokenRules(vararg rules: Pair<String, Double>, timeFactor: Int = 60) {

    this memberIs OK ifStateIn listOf(UPGRADE_FAILED, BROKEN, null, DEAD, BROKEN) andTest { machine -> rules.all { machine[it.first]?.D()?:(it.second + 1) < it.second } } after timeFactor / 2 seconds "machine-ok"

    this memberIs OK ifStateIn listOf(UPGRADE_FAILED, STALE) andTest { machine -> rules.all { machine[it.first]?.D()?:(it.second + 1) < it.second } } after timeFactor * 10 seconds "machine-ok"


    this memberIs UPGRADE_FAILED ifStateIn listOf(STALE) andTest { machine -> rules.any { machine[it.first]?.D()?:(it.second - 1) > it.second } } after  timeFactor * 3 seconds "downgrade"

    this memberIs BROKEN ifStateIn listOf(OK, OVERLOADED, BROKEN, null) andTest { machine -> rules.any { machine[it.first]?.D()?:(it.second - 1) > it.second } } after timeFactor seconds "machine-broken"
}


fun MachineGroup.addGroupSensorRules(vararg ranges: Pair<String, Range<Double>>, timeFactor: Int = 60) {

    this becomes GROUP_BROKEN ifStateIn  listOf(QUIET, BUSY, NORMAL) andTest { it.max != 0 && (it.workingSize() == 0 || it.activeSize() == 0 ) } after timeFactor seconds "group-size-dangerously-low"

    this becomes GROUP_BROKEN ifStateIn  listOf(null) andTest { it.max != 0 && (it.workingSize() == 0 || it.activeSize() == 0 ) } after timeFactor  seconds "group-size-dangerously-low-initial"

    this becomes GROUP_BROKEN ifStateIn  listOf(GROUP_BROKEN) andTest { it.max != 0 && (it.workingSize() == 0 || it.activeSize() == 0 ) } after timeFactor * 2 seconds "group-size-still-dangerously-low"

    this becomes NORMAL ifStateIn  listOf(GROUP_BROKEN) andTest { it.workingSize() > 0 && it.activeSize() > 0 } after timeFactor seconds "group-size-okay"


    this becomes BUSY ifStateIn  listOf(QUIET, BUSY, NORMAL, null) andTest { it.workingSize() < it.min } after timeFactor seconds "not-enough-working-machines-in-group"

    this becomes BUSY ifStateIn listOf(QUIET, BUSY, NORMAL, null) andTest {
    ranges.any { this[it.first]?:it.second.end > it.second.end }
    }  after timeFactor * 5 seconds "overload"

    this becomes QUIET ifStateIn listOf(QUIET, BUSY, NORMAL, null) andTest { this.workingSize() > this.max || this.machines().size() > this.hardMax }  after timeFactor seconds "too-many-machines"


    this becomes QUIET ifStateIn listOf(QUIET, BUSY, NORMAL, null) andTest {
        ranges.any { this[it.first]?:it.second.start < it.second.start }
    }  after timeFactor * 10 seconds "underload"

    this becomes NORMAL ifStateIn listOf(QUIET, BUSY, null) andTest {
    ranges.all { this[it.first]?:it.second.start in it.second }
        && this.workingSize() in this.min..this.max
    }  after timeFactor / 2 seconds "group-ok"

}
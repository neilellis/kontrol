package kontrol.impl.ocean

import kontrol.api.Monitor
import kontrol.api.MachineGroupState
import kontrol.api.MachineGroup
import kontrol.api.StateMachine
import kontrol.doclient.Droplet
import java.util.Timer
import kotlin.concurrent.*;
import java.util.HashMap
import kontrol.api.sensors.SensorArray
import kontrol.api.MonitorRule

/**
 * @todo document.
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */
public class DigitalOceanMachineGroupMonitor(val group: DigitalOceanMachineGroup, val sensorArray: SensorArray<Any?>) : Monitor<MachineGroupState, MachineGroup>{

    val timer = Timer("DOGroupMon", true);

    override fun start(target: MachineGroup, stateMachine: StateMachine<MachineGroupState, MachineGroup>, rules: List<MonitorRule<MachineGroupState, MachineGroup>>) {

        timer.schedule(1000, 5000) {
            rules.forEach { it.evaluate(target) }
        }

        timer.schedule(100, 30000) {
            try {
                val machines = HashMap<String, DigitalOceanMachine>();

                val dropletList: MutableList<Droplet>? = group.apiFactory.instance().getAvailableDroplets()
                if (dropletList != null) {
                    for (droplet in dropletList) {
                        if (droplet.name?.startsWith(group.config.machinePrefix + group.name)!!) {
                            val digitalOceanMachine = DigitalOceanMachine(droplet, group.apiFactory, sensorArray)
                            machines.put(droplet.id.toString(), digitalOceanMachine)
                        }

                    }
                }

                synchronized(group.machines) {
                    for (entry in machines) {
                        if (!group.machines.containsKey(entry.key)) {
                            entry.value.stateMachine.rules = group.defaultMachineRules;
                            entry.value.startMonitoring(group.machineMonitorRules);
                            group.machines.put(entry.key, entry.value);
                        }
                    }
                    for (machine in group.machines) {
                        if (!machines.containsKey(machine.key)) {
                            group.machines.remove(machine.key)?.stopMonitoring();
                        }
                    }
                }


            } catch (e: Throwable) {
                e.printStackTrace();
            }
        }

    }
    override fun stop() {
        timer.cancel();
    }
}
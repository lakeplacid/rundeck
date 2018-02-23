package rundeck.services

import com.dtolabs.rundeck.server.plugins.trigger.condition.QuartzSchedulerTaskTrigger
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.rundeck.core.tasks.TaskAction
import org.rundeck.core.tasks.TaskActionInvoker
import org.rundeck.core.tasks.TaskTrigger
import org.rundeck.core.tasks.TaskTriggerHandler
import rundeck.quartzjobs.RDTaskTriggerJob

/**
 * Handles trigger based schedules
 */
class ScheduledTaskTriggerService implements TaskTriggerHandler<RDTaskContext> {

    static transactional = false

    Scheduler quartzScheduler
    def scheduledExecutionService
    def executionService

    @Override
    boolean onStartup() {
        true
    }

    @Override
    boolean handlesTrigger(TaskTrigger trigger, RDTaskContext contextInfo) {
        trigger instanceof QuartzSchedulerTaskTrigger
    }


    @Override
    boolean registerTriggerForAction(
            String taskId,
            RDTaskContext contextInfo,
            TaskTrigger trigger,
            TaskAction action,
            TaskActionInvoker service
    ) {
        QuartzSchedulerTaskTrigger scheduled = (QuartzSchedulerTaskTrigger) trigger
        if (!scheduled.validSchedule) {
            log.info("scheduled task $taskId: not scheduling the Quartz Trigger of type $scheduled: not valid")
            return false
        }
        log.info(
                "schedule task using quartz for $taskId, context $contextInfo, trigger $trigger, action $action, invoker $service"
        )
        scheduleTrigger(taskId, contextInfo, scheduled, action, service)
        true
    }

    @Override
    void deregisterTriggerForAction(
            String taskId,
            RDTaskContext contextInfo,
            TaskTrigger condition,
            TaskAction action,
            TaskActionInvoker service
    ) {

        log.error(
                "deregister task from quartz for $taskId, context $contextInfo, condition $condition, action $action, invoker $service"
        )
        unscheduleTrigger(taskId, contextInfo)
    }

    boolean unscheduleTrigger(String taskId, RDTaskContext contextInfo) {

        def quartzJobName = taskId
        def quartzJobGroup = 'ScheduledTaskTriggerService'
        if (quartzScheduler.checkExists(JobKey.jobKey(quartzJobName, quartzJobGroup))) {
            log.info("Removing existing task $taskId with context $contextInfo: " + quartzJobName)

            return quartzScheduler.unscheduleJob(TriggerKey.triggerKey(quartzJobName, quartzJobGroup))
        }
        false
    }

    Date scheduleTrigger(
            String taskId,
            RDTaskContext contextInfo,
            QuartzSchedulerTaskTrigger scheduled,
            TaskAction action,
            TaskActionInvoker invoker
    ) {

        def quartzJobName = taskId
        def quartzJobGroup = 'ScheduledTaskTriggerService'
        def jobDesc = "Attempt to schedule job $quartzJobName with context $contextInfo"
        if (!executionService.executionsAreActive) {
            log.warn("$jobDesc, but executions are disabled.")
            return null
        }

        if (!scheduledExecutionService.shouldScheduleInThisProject(contextInfo.project)) {
            log.warn("$jobDesc, but project executions are disabled.")
            return null
        }


        def jobDetailBuilder = JobBuilder.newJob(RDTaskTriggerJob)
                                         .withIdentity(quartzJobName, quartzJobGroup)
//                .withDescription(scheduled.)
                                         .
                usingJobData(new JobDataMap(createJobDetailMap(taskId, contextInfo, scheduled, action, invoker)))


        def jobDetail = jobDetailBuilder.build()
        def triggerbuilder = TriggerBuilder.newTrigger().withIdentity(quartzJobName, quartzJobGroup)
        def trigger = scheduled.buildQuartzTrigger(triggerbuilder)

        def Date nextTime

        if (quartzScheduler.checkExists(JobKey.jobKey(quartzJobName, quartzJobGroup))) {
            log.info("rescheduling existing task $taskId with context $contextInfo: " + quartzJobName)

            nextTime = quartzScheduler.rescheduleJob(TriggerKey.triggerKey(quartzJobName, quartzJobGroup), trigger)
        } else {
            log.info("scheduling new task $taskId with context $contextInfo: " + quartzJobName)
            nextTime = quartzScheduler.scheduleJob(jobDetail, trigger)
        }

        log.info("scheduled task $taskId. next run: " + nextTime.toString())
        return nextTime
    }

    Map createJobDetailMap(
            String triggerId,
            RDTaskContext contextInfo,
            QuartzSchedulerTaskTrigger trigger,
            TaskAction action,
            TaskActionInvoker invoker
    ) {
        [
                triggerId: triggerId,
                context  : contextInfo,
                trigger  : trigger,
                action   : action,
                invoker  : invoker
        ]
    }
}

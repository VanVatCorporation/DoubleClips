package com.vanvatcorporation.doubleclips.helper;

import android.os.Handler;

import java.util.ArrayList;
import java.util.Arrays;

public class TaskHelper {
    public void RunNextTaskWhenCompletedPrevious(Handler handler, int latencyMs, Runnable... tasks)
    {
        new TaskExecutor(handler, latencyMs, tasks).ExecuteTasksSerially();
    }

    public class TaskExecutor
    {
        Handler handler;
        int latency = -1;
        ArrayList<Runnable> ongoingTasks = new ArrayList<>();
        public TaskExecutor(Handler handler, int latencyMs, Runnable... tasks)
        {
            ongoingTasks.addAll(Arrays.asList(tasks));
            this.handler = handler;
            this.latency = latencyMs;
        }
        public void ExecuteTasksSerially()
        {
            if(handler == null || latency == -1 || ongoingTasks == null) return;
            Runnable runnable = new Runnable() {
                int taskIndex = 0;
                @Override
                public void run() {
                    if(taskIndex >= ongoingTasks.size()) return;
                    ongoingTasks.get(taskIndex).run();
                    taskIndex++;
                    handler.postDelayed(this, latency);
                }
            };
            handler.post(runnable);
        }
    }
}

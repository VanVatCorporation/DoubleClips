package com.vanvatcorporation.doubleclips.constants;

import android.content.Context;
import android.view.View;

import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.impl.java.ArrayListImpl;

import java.io.Serializable;
import java.util.HashMap;

public class RunnableConstants {
    public static HashMap<RunnableInfo, Runnable> serviceRunnables = new HashMap<>();
    public static int assignTaskId = 0;

    /**
     * Assign a runnable task to the static memory.
     *
     * @param runnable the task to assign
     * @return a task ID in the map
     */
    public static RunnableInfo assignTaskToRunnable(Runnable runnable)
    {
        return assignTaskToRunnable(runnable, "title", "description");
    }

    /**
     * Assign a runnable task to the static memory.
     *
     * @param runnable the task to assign
     * @param title the task title
     * @param description the task description
     * @return a task ID in the map
     */
    public static RunnableInfo assignTaskToRunnable(Runnable runnable, String title, String description)
    {
        assignTaskId++;
        return assignTaskToRunnable(runnable, assignTaskId, title, description);
    }

    /**
     * Assign a runnable task to the static memory.
     *
     * @param runnable the task to assign
     * @param assignTaskId the task ID
     * @param title the task title
     * @param description the task description
     * @return a task ID in the map
     */
    public static RunnableInfo assignTaskToRunnable(Runnable runnable, int assignTaskId, String title, String description)
    {
        RunnableInfo runnableInfo = new RunnableInfo();
        runnableInfo.id = assignTaskId;
        runnableInfo.title = title;
        runnableInfo.description = description;
        serviceRunnables.put(runnableInfo, runnable);
        return runnableInfo;
    }

    /**
     * Get runnable task from the runnable info's id.
     *
     * @param id the task ID to find
     * @return a Runnable from the set, null if unavailable
     */
    public static Runnable getTaskFromId(int id)
    {
        RunnableInfo toGet = null;

        for (RunnableInfo task : serviceRunnables.keySet()) {
            if (task.id == id) {
                toGet = task;
                break;
            }
        }

        if (toGet != null) {
            return serviceRunnables.get(toGet);
        }
        return null;
    }

    /**
     * Report completion for the task.
     *
     * @param id the ID of the task to delete
     * @return whether the completion and removal of the task is success or not
     */
    public static boolean reportCompleteTask(int id)
    {
        try
        {
            RunnableInfo toRemove = null;

            for (RunnableInfo task : serviceRunnables.keySet()) {
                if (task.id == id) {
                    toRemove = task;
                    break;
                }
            }

            if (toRemove != null) {
                serviceRunnables.remove(toRemove);
                return true;
            }
            else
                return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }


    public static class RunnableInfo implements Serializable
    {
        public int id;
        public String title, description;
    }
}

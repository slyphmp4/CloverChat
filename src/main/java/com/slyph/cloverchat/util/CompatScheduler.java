package com.slyph.cloverchat.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CompatScheduler {

    private final JavaPlugin plugin;
    private final boolean folia;
    private final Object globalRegionScheduler;
    private final Object asyncScheduler;

    public CompatScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
        this.globalRegionScheduler = resolveGlobalRegionScheduler();
        this.asyncScheduler = resolveAsyncScheduler();
    }

    public boolean isFolia() {
        return folia;
    }

    public TaskHandle runGlobal(Runnable runnable) {
        if (runnable == null) {
            return null;
        }

        if (folia && globalRegionScheduler != null) {
            Object task = invokeGlobalRun(globalRegionScheduler, runnable);
            if (task != null) {
                return wrap(task);
            }
        }

        BukkitTask task = runBukkitSync(runnable);
        return wrap(task);
    }

    public TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        if (runnable == null) {
            return null;
        }

        long delay = Math.max(0L, delayTicks);
        if (folia && globalRegionScheduler != null) {
            Object task = invokeGlobalDelayed(globalRegionScheduler, runnable, delay);
            if (task != null) {
                return wrap(task);
            }
        }

        BukkitTask task = runBukkitSyncLater(runnable, delay);
        return wrap(task);
    }

    public TaskHandle runGlobalRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        if (runnable == null) {
            return null;
        }

        long delay = Math.max(0L, delayTicks);
        long period = Math.max(1L, periodTicks);

        if (folia && globalRegionScheduler != null) {
            Object task = invokeGlobalRepeating(globalRegionScheduler, runnable, delay, period);
            if (task != null) {
                return wrap(task);
            }
        }

        BukkitTask task = runBukkitSyncRepeating(runnable, delay, period);
        return wrap(task);
    }

    public TaskHandle runAsync(Runnable runnable) {
        if (runnable == null) {
            return null;
        }

        if (folia && asyncScheduler != null) {
            Object task = invokeAsyncRun(asyncScheduler, runnable);
            if (task != null) {
                return wrap(task);
            }
        }

        BukkitTask task = runBukkitAsync(runnable);
        return wrap(task);
    }

    public TaskHandle runAsyncRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        if (runnable == null) {
            return null;
        }

        long delay = Math.max(0L, delayTicks);
        long period = Math.max(1L, periodTicks);

        if (folia && asyncScheduler != null) {
            Object task = invokeAsyncRepeating(asyncScheduler, runnable, delay, period);
            if (task != null) {
                return wrap(task);
            }
        }

        BukkitTask task = runBukkitAsyncRepeating(runnable, delay, period);
        return wrap(task);
    }

    public TaskHandle runEntity(Entity entity, Runnable runnable) {
        return runEntityLater(entity, 0L, runnable);
    }

    public TaskHandle runEntityLater(Entity entity, long delayTicks, Runnable runnable) {
        if (entity == null || runnable == null) {
            return null;
        }

        long delay = Math.max(0L, delayTicks);

        if (folia) {
            Object scheduler = resolveEntityScheduler(entity);
            if (scheduler != null) {
                Object task = delay <= 0L
                        ? invokeEntityRun(scheduler, runnable)
                        : invokeEntityDelayed(scheduler, runnable, delay);
                if (task != null) {
                    return wrap(task);
                }
            }
            return runGlobalLater(runnable, delay);
        }

        BukkitTask task = runBukkitSyncLater(runnable, delay);
        return wrap(task);
    }

    public TaskHandle runEntityRepeating(Entity entity, long delayTicks, long periodTicks, Runnable runnable) {
        if (entity == null || runnable == null) {
            return null;
        }

        long delay = Math.max(0L, delayTicks);
        long period = Math.max(1L, periodTicks);

        if (folia) {
            Object scheduler = resolveEntityScheduler(entity);
            if (scheduler != null) {
                Object task = invokeEntityRepeating(scheduler, runnable, delay, period);
                if (task != null) {
                    return wrap(task);
                }
            }
            return runGlobalRepeating(runnable, delay, period);
        }

        BukkitTask task = runBukkitSyncRepeating(runnable, delay, period);
        return wrap(task);
    }

    private BukkitTask runBukkitSync(Runnable runnable) {
        try {
            return Bukkit.getScheduler().runTask(plugin, runnable);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BukkitTask runBukkitSyncLater(Runnable runnable, long delay) {
        try {
            return Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BukkitTask runBukkitSyncRepeating(Runnable runnable, long delay, long period) {
        try {
            return Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BukkitTask runBukkitAsync(Runnable runnable) {
        try {
            return Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BukkitTask runBukkitAsyncRepeating(Runnable runnable, long delay, long period) {
        try {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delay, period);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean detectFolia() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object resolveGlobalRegionScheduler() {
        if (!folia) {
            return null;
        }
        try {
            Method method = Bukkit.class.getMethod("getGlobalRegionScheduler");
            return method.invoke(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object resolveAsyncScheduler() {
        if (!folia) {
            return null;
        }
        try {
            Method method = Bukkit.class.getMethod("getAsyncScheduler");
            return method.invoke(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object resolveEntityScheduler(Entity entity) {
        try {
            Method method = entity.getClass().getMethod("getScheduler");
            return method.invoke(entity);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeGlobalRun(Object scheduler, Runnable runnable) {
        Consumer<Object> consumer = task -> runnable.run();
        Object task = invokeMethod(scheduler, "run",
                new Class[]{Plugin.class, Consumer.class},
                new Object[]{plugin, consumer});
        if (task != null) {
            return task;
        }
        invokeMethod(scheduler, "execute",
                new Class[]{Plugin.class, Runnable.class},
                new Object[]{plugin, runnable});
        return null;
    }

    private Object invokeGlobalDelayed(Object scheduler, Runnable runnable, long delayTicks) {
        Consumer<Object> consumer = task -> runnable.run();
        return invokeMethod(scheduler, "runDelayed",
                new Class[]{Plugin.class, Consumer.class, long.class},
                new Object[]{plugin, consumer, delayTicks});
    }

    private Object invokeGlobalRepeating(Object scheduler, Runnable runnable, long delayTicks, long periodTicks) {
        Consumer<Object> consumer = task -> runnable.run();
        return invokeMethod(scheduler, "runAtFixedRate",
                new Class[]{Plugin.class, Consumer.class, long.class, long.class},
                new Object[]{plugin, consumer, delayTicks, periodTicks});
    }

    private Object invokeAsyncRun(Object scheduler, Runnable runnable) {
        Consumer<Object> consumer = task -> runnable.run();
        return invokeMethod(scheduler, "runNow",
                new Class[]{Plugin.class, Consumer.class},
                new Object[]{plugin, consumer});
    }

    private Object invokeAsyncRepeating(Object scheduler, Runnable runnable, long delayTicks, long periodTicks) {
        Consumer<Object> consumer = task -> runnable.run();
        long delayMillis = delayTicks * 50L;
        long periodMillis = periodTicks * 50L;
        return invokeMethod(scheduler, "runAtFixedRate",
                new Class[]{Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class},
                new Object[]{plugin, consumer, delayMillis, periodMillis, TimeUnit.MILLISECONDS});
    }

    private Object invokeEntityRun(Object scheduler, Runnable runnable) {
        Consumer<Object> consumer = task -> runnable.run();
        Runnable retired = () -> {
        };
        Object task = invokeMethod(scheduler, "run",
                new Class[]{Plugin.class, Consumer.class, Runnable.class},
                new Object[]{plugin, consumer, retired});
        if (task != null) {
            return task;
        }
        return invokeMethod(scheduler, "runDelayed",
                new Class[]{Plugin.class, Consumer.class, Runnable.class, long.class},
                new Object[]{plugin, consumer, retired, 0L});
    }

    private Object invokeEntityDelayed(Object scheduler, Runnable runnable, long delayTicks) {
        Consumer<Object> consumer = task -> runnable.run();
        Runnable retired = () -> {
        };
        return invokeMethod(scheduler, "runDelayed",
                new Class[]{Plugin.class, Consumer.class, Runnable.class, long.class},
                new Object[]{plugin, consumer, retired, delayTicks});
    }

    private Object invokeEntityRepeating(Object scheduler, Runnable runnable, long delayTicks, long periodTicks) {
        Consumer<Object> consumer = task -> runnable.run();
        Runnable retired = () -> {
        };
        return invokeMethod(scheduler, "runAtFixedRate",
                new Class[]{Plugin.class, Consumer.class, Runnable.class, long.class, long.class},
                new Object[]{plugin, consumer, retired, delayTicks, periodTicks});
    }

    private Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object[] args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private TaskHandle wrap(Object rawTask) {
        if (rawTask == null) {
            return null;
        }
        return new TaskHandle(rawTask);
    }

    public static final class TaskHandle {
        private final Object rawTask;

        private TaskHandle(Object rawTask) {
            this.rawTask = rawTask;
        }

        public void cancel() {
            if (rawTask == null) {
                return;
            }
            try {
                Method method = rawTask.getClass().getMethod("cancel");
                method.invoke(rawTask);
            } catch (Exception ignored) {
            }
        }
    }
}

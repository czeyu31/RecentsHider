import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.TaskInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceManager;
import java.util.List;

public class TaskHideUtil {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: TaskHideUtil <package_name> <hide|show>");
            System.exit(1);
        }

        String packageName = args[0];
        boolean hide = "hide".equals(args[1]);

        IActivityManager am = IActivityManager.Stub.asInterface(
            ServiceManager.getService("activity"));

        ActivityManager amClient = null;

        // Get running tasks to find the task token
        // Use reflection to access hidden API
        Class<?> amClass = Class.forName("android.app.ActivityManager");
        Class<?> taskInfoClass = Class.forName("android.app.ActivityManager$RecentTaskInfo");

        // Get IActivityManager binder
        IBinder activityBinder = ServiceManager.getService("activity");
        IBinder proxy = activityBinder;

        // Call setInvisible via reflection on IActivityManager$Stub
        Class<?> stubClass = Class.forName("android.app.IActivityManager$Stub");
        java.lang.reflect.Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
        Object iam = asInterfaceMethod.invoke(null, proxy);

        // Find the method setInvisible(IBinder token, boolean invisible)
        // First, we need to find the task token for the package
        // We'll use dumpsys approach via reading /proc/self/fd or just parse the output

        // Alternative: use the hidden ActivityManager API
        // android.app.ActivityManager.getService() returns IActivityManager
        // But we need the token...

        // Actually, let's use a different approach: call setInvisible with the process name
        // The system should be able to resolve it

        try {
            // Try to find setInvisible method
            java.lang.reflect.Method setInvisibleMethod = null;
            for (java.lang.reflect.Method m : iam.getClass().getMethods()) {
                if (m.getName().equals("setInvisible")) {
                    setInvisibleMethod = m;
                    break;
                }
            }

            if (setInvisibleMethod == null) {
                System.err.println("setInvisible method not found");
                // Try alternative: setTaskHidden or similar
                for (java.lang.reflect.Method m : iam.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("hidden") || 
                        m.getName().toLowerCase().contains("invisible") ||
                        m.getName().toLowerCase().contains("recent")) {
                        System.out.println("Found method: " + m.getName() + " params: " + java.util.Arrays.toString(m.getParameterTypes()));
                    }
                }
                System.exit(1);
            }

            System.out.println("Found setInvisible: " + setInvisibleMethod);
            System.out.println("Parameters: " + java.util.Arrays.toString(setInvisibleMethod.getParameterTypes()));

            // We need the task token - let's try to get it via getRunningTasks
            // Use reflection since these are hidden APIs
            Class<?> activityManagerNativeClass = Class.forName("android.app.ActivityManagerNative");
            // Actually, let's try a simpler approach - just try passing null token
            // and the package name won't work that way

            // Let's enumerate all methods to understand the API better
            System.out.println("\nAll IActivityManager methods containing 'task' or 'invisible' or 'visible' or 'recent':");
            for (java.lang.reflect.Method m : iam.getClass().getMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("task") || name.contains("invisible") || 
                    name.contains("visible") || name.contains("recent") ||
                    name.contains("hidden")) {
                    System.out.println("  " + m.getName() + "(" + 
                        java.util.Arrays.toString(m.getParameterTypes()) + ")");
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

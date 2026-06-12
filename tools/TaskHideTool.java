import java.lang.reflect.Method;

public class TaskHideTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: TaskHideTool <package_name> <hide|show>");
            System.exit(1);
        }

        String packageName = args[0];
        boolean hide = "hide".equals(args[1]);

        // ServiceManager.getService("activity")
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method getServiceMethod = smClass.getMethod("getService", String.class);
        Object activityBinder = getServiceMethod.invoke(null, "activity");

        // IActivityManager.Stub.asInterface(binder)
        Class<?> stubClass = Class.forName("android.app.IActivityManager$Stub");
        Method asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder.class);
        Object iam = asInterfaceMethod.invoke(null, activityBinder);

        // Find setInvisible(IBinder, boolean) method
        Method setInvisibleMethod = null;
        for (Method m : iam.getClass().getMethods()) {
            if (m.getName().equals("setInvisible") && m.getParameterTypes().length == 2) {
                setInvisibleMethod = m;
                break;
            }
        }

        if (setInvisibleMethod == null) {
            System.err.println("setInvisible not found");
            System.exit(1);
        }

        // Get tasks via getTasks(int maxNum)
        Method getTasksMethod = null;
        for (Method m : iam.getClass().getMethods()) {
            if (m.getName().equals("getTasks") && m.getParameterTypes().length == 1) {
                getTasksMethod = m;
                break;
            }
        }

        if (getTasksMethod == null) {
            System.err.println("getTasks not found");
            System.exit(1);
        }

        @SuppressWarnings("unchecked")
        java.util.List<Object> tasks = (java.util.List<Object>) getTasksMethod.invoke(iam, 200);

        if (tasks == null || tasks.isEmpty()) {
            System.err.println("No tasks found");
            System.exit(1);
        }

        boolean found = false;
        for (Object taskInfo : tasks) {
            // Get baseIntent
            java.lang.reflect.Field baseIntentField = null;
            for (java.lang.reflect.Field f : taskInfo.getClass().getFields()) {
                if (f.getName().equals("baseIntent")) {
                    baseIntentField = f;
                    break;
                }
            }
            if (baseIntentField == null) continue;

            Object baseIntent = baseIntentField.get(taskInfo);
            if (baseIntent == null) continue;

            Method getComponentMethod = baseIntent.getClass().getMethod("getComponent");
            Object component = getComponentMethod.invoke(baseIntent);
            if (component == null) continue;

            Method getPackageNameMethod = component.getClass().getMethod("getPackageName");
            String taskPackage = (String) getPackageNameMethod.invoke(component);

            if (packageName.equals(taskPackage)) {
                // Find IBinder field (token)
                java.lang.reflect.Field tokenField = null;
                for (java.lang.reflect.Field f : taskInfo.getClass().getFields()) {
                    if (android.os.IBinder.class.isAssignableFrom(f.getType())) {
                        tokenField = f;
                        break;
                    }
                }

                if (tokenField != null) {
                    android.os.IBinder token = (android.os.IBinder) tokenField.get(taskInfo);
                    if (token != null) {
                        setInvisibleMethod.invoke(iam, token, hide);
                        System.out.println((hide ? "HIDDEN" : "SHOWN") + ": " + packageName);
                        found = true;
                    }
                }
                break;
            }
        }

        if (!found) {
            System.err.println("Task not found for: " + packageName);
            System.exit(1);
        }
    }
}

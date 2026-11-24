# Framework-only app; nothing reflective to keep beyond what the manifest references, except:
# the helper, which Android starts by class name under the shell user (see helper/Helper.kt).
-keep class dev.tabdesk.helper.Helper { public static void main(java.lang.String[]); }

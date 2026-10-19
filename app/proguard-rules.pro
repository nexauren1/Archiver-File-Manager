# Shizuku loads its UserService by class name; keep the implementation and AIDL stubs.
-keep class com.nexauren.archiver.RestrictedStorageUserService { public <init>(); *; }
-keep interface com.nexauren.archiver.IRestrictedStorageService { *; }
-keep class com.nexauren.archiver.IRestrictedStorageService$Stub { *; }
-keep class com.nexauren.archiver.IRestrictedStorageService$Stub$Proxy { *; }

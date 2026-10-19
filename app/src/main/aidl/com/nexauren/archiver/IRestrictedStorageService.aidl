package com.nexauren.archiver;

interface IRestrictedStorageService {
    void destroy() = 16777114;
    String[] getRestrictedRootPaths();
    String getAccessIdentity();
    String listDirectory(String path);
    String readTextFile(String path);
    boolean writeTextFile(String path, String content);
    boolean createDirectory(String path);
    boolean deletePath(String path);
    boolean renamePath(String path, String newName);
}

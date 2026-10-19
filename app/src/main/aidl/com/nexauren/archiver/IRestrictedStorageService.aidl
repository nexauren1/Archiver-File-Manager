package com.nexauren.archiver;

interface IRestrictedStorageService {
    void destroy() = 16777114;
    String[] getRestrictedRootPaths() = 1;
    String getAccessIdentity() = 2;
    String listDirectory(String path) = 3;
    String readTextFile(String path) = 4;
    boolean writeTextFile(String path, String content) = 5;
    boolean createDirectory(String path) = 6;
    boolean deletePath(String path) = 7;
    boolean renamePath(String path, String newName) = 8;
}

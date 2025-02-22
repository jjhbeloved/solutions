import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class NioTest {
    // 定义同步方式的枚举
    public enum SyncType {
        NONE,       // 不同步
        SYNC,       // fsync
        DSYNC,      // fdatasync
        SYNC_FILE   // sync
    }

    // 定义写入模式的枚举
    public enum WriteMode {
        APPEND,     // 追加写入
        OVERWRITE   // 覆盖写入
    }

    /**
     * 写入文件，支持指定路径和同步方式
     * @param content 要写入的内容
     * @param filePath 文件路径，如果为null则写入随机文件
     * @param syncType 同步方式
     */
    public static void writeToFile(String content, String filePath, SyncType syncType) {
        FileChannel channel = null;
        try {
            // 确定文件路径
            String fileName;
            if (filePath == null || filePath.trim().isEmpty()) {
                fileName = "tmp/" + UUID.randomUUID().toString();
                // 确保tmp目录存在
                Files.createDirectories(Paths.get("tmp"));
            } else {
                fileName = filePath;
                // 确保目标文件的父目录存在
                Path parent = Paths.get(fileName).getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            
            // 根据同步类型设置打开选项
            Path path = Paths.get(fileName);
            StandardOpenOption[] options;
            switch (syncType) {
                case SYNC:
                    options = new StandardOpenOption[]{
                        StandardOpenOption.CREATE,          // 如果不存在则创建
                        StandardOpenOption.WRITE,           // 写入模式
                        StandardOpenOption.APPEND,          // 追加模式
                        StandardOpenOption.SYNC
                    };
                    break;
                case DSYNC:
                    options = new StandardOpenOption[]{
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.DSYNC
                    };
                    break;
                case SYNC_FILE:
                    options = new StandardOpenOption[]{
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                    };
                    break;
                default:
                    options = new StandardOpenOption[]{
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                    };
            }
            
            // 打开文件通道
            channel = FileChannel.open(path, options);
            
            // 写入内容
            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes());
            channel.write(buffer);
            
            // 如果是SYNC_FILE类型，手动调用force
            if (syncType == SyncType.SYNC_FILE && channel != null) {
                channel.force(true);  // true表示同时更新文件内容和元数据
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 为了保持兼容性，保留原来的方法
    public static void writeToFile(String content) {
        writeToFile(content, null, SyncType.NONE);
    }

    // 重载方法，只指定内容和同步方式
    public static void writeToFile(String content, SyncType syncType) {
        writeToFile(content, null, syncType);
    }

    /**
     * 增强版文件写入函数
     * @param content 要写入的内容
     * @param filePath 文件路径（必须指定）
     * @param writeMode 写入模式（追加/覆盖）
     * @param syncType 同步方式
     * @return 返回写入后的文件位置（字节偏移量）
     * @throws IllegalArgumentException 如果文件路径为空
     */
    public static long writeToFileEnhanced(String content, String filePath, 
            WriteMode writeMode, SyncType syncType) throws IllegalArgumentException {
        
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }

        FileChannel channel = null;
        long position = -1;
        
        try {
            // 确保目标文件的父目录存在
            Path path = Paths.get(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            
            // 设置写入选项
            StandardOpenOption[] options;
            if (writeMode == WriteMode.APPEND) {
                options = getOptionsWithAppend(syncType);
            } else {
                options = getOptionsWithTruncate(syncType);
            }
            
            // 打开文件通道
            channel = FileChannel.open(path, options);
            
            // 获取写入前的位置
            position = channel.position();
            
            // 写入内容
            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes());
            channel.write(buffer);
            
            // 获取写入后的位置
            position = channel.position();
            
            // 如果是SYNC_FILE类型，手动调用force
            if (syncType == SyncType.SYNC_FILE) {
                channel.force(true);
            }
            
            return position;
            
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    // 获取追加写入的选项
    private static StandardOpenOption[] getOptionsWithAppend(SyncType syncType) {
        switch (syncType) {
            case SYNC:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.SYNC
                };
            case DSYNC:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.DSYNC
                };
            default:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
                };
        }
    }
    
    // 获取覆盖写入的选项
    private static StandardOpenOption[] getOptionsWithTruncate(SyncType syncType) {
        switch (syncType) {
            case SYNC:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC
                };
            case DSYNC:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.DSYNC
                };
            default:
                return new StandardOpenOption[]{
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                };
        }
    }

    /**
     * 打开文件通道
     * @param filePath 文件路径（必须指定）
     * @param writeMode 写入模式（追加/覆盖）
     * @param syncType 同步方式
     * @return 返回打开的FileChannel对象
     * @throws IOException 当发生IO错误时
     * @throws IllegalArgumentException 如果文件路径为空
     */
    public static FileChannel openFileChannel(String filePath, 
            WriteMode writeMode, SyncType syncType) throws IOException, IllegalArgumentException {
        
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }

        try {
            // 确保目标文件的父目录存在
            Path path = Paths.get(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            
            // 设置基础选项：读写权限
            Set<StandardOpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.CREATE);    // 如果不存在则创建
            options.add(StandardOpenOption.READ);      // 添加读取权限
            options.add(StandardOpenOption.WRITE);     // 写入权限
            
            // 根据写入模式添加特定选项
            if (writeMode == WriteMode.APPEND) {
                options.add(StandardOpenOption.APPEND);
            } else {
                options.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            
            // 添加同步选项
            switch (syncType) {
                case SYNC:
                    options.add(StandardOpenOption.SYNC);
                    break;
                case DSYNC:
                    options.add(StandardOpenOption.DSYNC);
                    break;
                default:
                    break;
            }
            
            // 打开并返回文件通道
            return FileChannel.open(path, options.toArray(new StandardOpenOption[0]));
            
        } catch (IOException e) {
            throw e;  // 向上传递IO异常
        }
    }
}
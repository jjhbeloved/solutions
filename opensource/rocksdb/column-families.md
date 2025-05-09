# RocksDB中的列族(Column Families)

## 目录

- [RocksDB中的列族(Column Families)](#rocksdb中的列族column-families)
  - [目录](#目录)
  - [1. 列族的概念和使用场景](#1-列族的概念和使用场景)
    - [1.1 列族的基本定义](#11-列族的基本定义)
    - [1.2 默认列族](#12-默认列族)
    - [1.3 列族的核心使用场景](#13-列族的核心使用场景)
  - [2. 多列族管理](#2-多列族管理)
    - [2.1 创建与打开列族](#21-创建与打开列族)
    - [2.2 访问列族数据](#22-访问列族数据)
    - [2.3 删除列族](#23-删除列族)
    - [2.4 列族的内部结构](#24-列族的内部结构)
  - [3. 列族选项设置](#3-列族选项设置)
    - [3.1 通用选项](#31-通用选项)
    - [3.2 高级选项](#32-高级选项)
    - [3.3 选项优化策略](#33-选项优化策略)
  - [4. 列族的生命周期](#4-列族的生命周期)
    - [4.1 创建阶段](#41-创建阶段)
    - [4.2 使用阶段](#42-使用阶段)
    - [4.3 删除阶段](#43-删除阶段)
    - [4.4 资源管理考量](#44-资源管理考量)

## 1. 列族的概念和使用场景

### 1.1 列族的基本定义

列族(Column Families)是RocksDB中数据组织的基本单位，它允许在同一个RocksDB数据库中维护多个相互独立的键值空间。每个列族都有：

- 独立的键值命名空间
- 独立的设置选项（如压缩、合并操作等）
- 独立的内存表(MemTable)和持久化数据文件(SST文件)
- 共享的WAL(Write-Ahead Log)文件（可配置为独立）

列族可以看作是逻辑上分离但物理上共存的"子数据库"，它们共享同一个物理存储路径和部分系统资源。

下图展示了RocksDB中列族与其他组件之间的关系结构：

[RocksDB列族结构关系图](docs/cf/column_family_structure.puml)

### 1.2 默认列族

所有RocksDB数据库都至少有一个列族，称为"默认列族"(Default Column Family)。在RocksDB的代码实现中，默认列族的名称为常量：

```cpp
extern const std::string kDefaultColumnFamilyName;  // 值为"default"
```

当不指定列族进行操作时，所有的数据读写都在默认列族中进行。

### 1.3 列族的核心使用场景

列族设计的主要使用场景包括：

1. **数据隔离**：将不同类型或用途的数据隔离存储，降低相互影响
2. **差异化配置**：为不同数据类型设置最优的存储、压缩和缓存策略
3. **原子性操作**：跨列族的写入可以保证原子性，同时提交或回滚
4. **单独备份和恢复**：可以针对特定列族进行备份恢复操作
5. **数据生命周期管理**：不同生命周期的数据可以放在不同列族，方便整体删除
6. **性能调优**：对热点数据和冷数据使用不同的列族和配置，优化整体性能

典型应用举例：
- 将索引数据和原始数据分开存储在不同列族
- 将临时数据和永久数据放在不同列族
- 按数据访问频率分类存储，频繁访问的放入优化读取的列族

## 2. 多列族管理

### 2.1 创建与打开列族

RocksDB提供了几种方式创建和打开列族：

1. **数据库创建时打开列族**：

```cpp
std::vector<ColumnFamilyDescriptor> column_families;
// 默认列族必须包含
column_families.push_back(ColumnFamilyDescriptor(
    kDefaultColumnFamilyName, ColumnFamilyOptions()));
column_families.push_back(ColumnFamilyDescriptor(
    "new_cf", ColumnFamilyOptions()));
    
std::vector<ColumnFamilyHandle*> handles;
DB* db;
Status s = DB::Open(DBOptions(), db_path, column_families, &handles, &db);
```

2. **动态创建新列族**：

```cpp
ColumnFamilyHandle* cf_handle;
Status s = db->CreateColumnFamily(ColumnFamilyOptions(), "new_cf", &cf_handle);
```

3. **列出现有列族**：

```cpp
std::vector<std::string> cf_names;
Status s = DB::ListColumnFamilies(DBOptions(), db_path, &cf_names);
```

### 2.2 访问列族数据

一旦获取了列族句柄(ColumnFamilyHandle)，就可以通过它访问特定列族的数据：

```cpp
// 写入数据到特定列族
Status s = db->Put(WriteOptions(), cf_handle, key, value);

// 从特定列族读取数据
std::string value;
Status s = db->Get(ReadOptions(), cf_handle, key, &value);

// 从特定列族删除数据
Status s = db->Delete(WriteOptions(), cf_handle, key);

// 创建特定列族的迭代器
Iterator* it = db->NewIterator(ReadOptions(), cf_handle);
```

RocksDB也支持跨列族的原子写入操作，使用WriteBatch：

```cpp
WriteBatch batch;
batch.Put(cf_handle1, key1, value1);
batch.Put(cf_handle2, key2, value2);
batch.Delete(cf_handle1, key3);
Status s = db->Write(WriteOptions(), &batch);
```

### 2.3 删除列族

删除列族是两步操作：

1. 调用DropColumnFamily标记要删除的列族
2. 删除ColumnFamilyHandle对象释放资源

```cpp
// 标记删除列族
Status s = db->DropColumnFamily(cf_handle);

// 释放列族句柄资源
delete cf_handle;
```

注意：DropColumnFamily只是标记列族被删除，数据不会立即从磁盘上清除，而是在后续压缩过程中逐渐被清理。

### 2.4 列族的内部结构

每个列族在RocksDB内部由ColumnFamilyData对象表示，它包含：

- **memtable**：当前活跃的内存表
- **imm**：不可变内存表列表(等待刷盘)
- **版本管理**：对应的Version和版本管理
- **TableCache**：表缓存
- **选项配置**：该列族的全部配置选项

RocksDB通过ColumnFamilySet管理所有列族，它维护了列族名称到ID的映射，以及ID到ColumnFamilyData对象的映射。

## 3. 列族选项设置

### 3.1 通用选项

ColumnFamilyOptions中的关键选项包括：

- **write_buffer_size**：单个memtable的大小限制（默认64MB）
- **max_write_buffer_number**：最大memtable数量
- **compression**：SST文件压缩算法
- **compaction_style**：压缩策略(level, universal, fifo)
- **prefix_extractor**：前缀提取器，用于优化前缀查询
- **comparator**：自定义键比较器
- **merge_operator**：自定义合并操作符
- **table_factory**：表格式工厂（如BlockBasedTableFactory）

```cpp
ColumnFamilyOptions cf_options;
cf_options.write_buffer_size = 128 * 1024 * 1024;  // 128MB
cf_options.max_write_buffer_number = 3;
cf_options.compression = kSnappyCompression;
cf_options.compaction_style = kCompactionStyleLevel;
```

### 3.2 高级选项

高级选项通过AdvancedColumnFamilyOptions进行设置，关键选项包括：

- **level0_file_num_compaction_trigger**：触发L0压缩的文件数
- **max_bytes_for_level_base**：L1层的最大字节数
- **max_bytes_for_level_multiplier**：每层大小的乘数因子
- **target_file_size_base**：SST文件的目标大小
- **bloom_locality**：布隆过滤器的局部性参数
- **arena_block_size**：Arena内存分配器的块大小
- **disable_auto_compactions**：是否禁用自动压缩

```cpp
cf_options.level0_file_num_compaction_trigger = 4;
cf_options.max_bytes_for_level_base = 256 * 1048576;  // 256MB
cf_options.target_file_size_base = 64 * 1048576;  // 64MB
cf_options.disable_auto_compactions = false;
```

### 3.3 选项优化策略

RocksDB提供了预设的优化策略函数：

1. **OptimizeForPointLookup**：优化点查询性能
   ```cpp
   cf_options.OptimizeForPointLookup(block_cache_size_mb);
   ```

2. **OptimizeForSmallDb**：为小型数据库优化内存使用
   ```cpp
   cf_options.OptimizeForSmallDb(&cache);
   ```

3. **OptimizeLevelStyleCompaction**：优化层级式压缩
   ```cpp
   cf_options.OptimizeLevelStyleCompaction(memtable_memory_budget);
   ```

4. **OptimizeUniversalStyleCompaction**：优化通用式压缩
   ```cpp
   cf_options.OptimizeUniversalStyleCompaction(memtable_memory_budget);
   ```

## 4. 列族的生命周期

### 4.1 创建阶段

列族的创建过程包括：

1. 在ColumnFamilySet中分配唯一ID
2. 创建ColumnFamilyData对象
3. 初始化memtable和版本管理数据结构
4. 注册到全局列族集合中
5. 返回ColumnFamilyHandle给用户

在RocksDB内部实现中，创建列族时会调用CreateColumnFamily方法：

```cpp
ColumnFamilyData* ColumnFamilySet::CreateColumnFamily(
    const std::string& name, uint32_t id, Version* dummy_versions,
    const ColumnFamilyOptions& options) {
  // 创建新的ColumnFamilyData
  ColumnFamilyData* new_cfd = new ColumnFamilyData(...);
  
  // 注册到全局映射中
  column_families_.insert({name, id});
  column_family_data_.insert({id, new_cfd});
  
  // 更新最大列族ID
  max_column_family_ = std::max(max_column_family_, id);
  
  // 加入链表
  new_cfd->next_ = dummy_cfd_;
  auto prev = dummy_cfd_->prev_;
  new_cfd->prev_ = prev;
  prev->next_ = new_cfd;
  dummy_cfd_->prev_ = new_cfd;
  
  return new_cfd;
}
```

### 4.2 使用阶段

列族使用阶段的关键操作包括：

1. **数据写入**：通过Put/Merge/Delete API写入数据到列族
2. **数据读取**：通过Get/MultiGet/NewIterator API读取列族数据
3. **内存数据刷盘**：memtable满后转为不可变，刷盘到SST文件
4. **后台压缩**：根据列族配置进行SST文件的压缩和整理
5. **选项动态调整**：通过SetOptions API动态调整部分列族选项

使用过程中，每个列族的MemTable、不可变MemTable和SST文件都是独立维护的，但它们共享WAL（除非配置了独立WAL）。

### 4.3 删除阶段

列族的删除是分两步进行的：

1. **标记删除**：调用DropColumnFamily标记列族为"已删除"
   ```cpp
   // 内部实现
   Status DBImpl::DropColumnFamily(ColumnFamilyHandle* column_family) {
     // 获取列族数据
     auto cfd = reinterpret_cast<ColumnFamilyHandleImpl*>(column_family)->cfd();
     
     // 标记为已删除
     cfd->SetDropped();
     
     // 从metadata中移除
     // 但不立即清理数据文件
   }
   ```

2. **资源释放**：删除ColumnFamilyHandle对象释放资源
   ```cpp
   // 内部实现（部分代码）
   ColumnFamilyData::~ColumnFamilyData() {
     // 从链表中移除
     auto prev = prev_;
     auto next = next_;
     prev->next_ = next;
     next->prev_ = prev;
     
     // 从ColumnFamilySet中移除
     if (!dropped_ && column_family_set_ != nullptr) {
       column_family_set_->RemoveColumnFamily(this);
     }
     
     // 清理版本、内存表等资源
   }
   ```

删除后的数据文件清理是渐进式的，通过后台压缩过程逐步完成。

### 4.4 资源管理考量

使用列族时需要注意以下资源管理问题：

1. **内存使用**：每个列族有独立的memtable，增加列族会增加内存使用
2. **文件描述符**：更多的列族可能导致更多的SST文件和更多的文件描述符
3. **性能影响**：列族过多可能导致写放大增加和读性能下降
4. **资源释放**：必须正确删除ColumnFamilyHandle避免资源泄露
5. **备份与恢复**：必须一致地备份和恢复多列族数据库

最佳实践建议：
- 列族数量控制在合理范围内（通常不超过几十个）
- 根据数据访问模式和生命周期划分列族
- 为不同列族设置合适的配置，避免全部采用默认值
- 定期监控各列族的性能指标和资源使用情况 
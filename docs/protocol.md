# Enigma protocol
Enigma uses TCP sockets for communication. Data is sent in each direction as a continuous stream, with packets being
concatenated one after the other.

In this document, data will be represented in C-like pseudocode. The primitive data types will be the same as those
defined by Java's [DataOutputStream](https://docs.oracle.com/javase/7/docs/api/java/io/DataOutputStream.html), i.e. in
big-endian order for multi-byte integers (`short`, `int` and `long`) and a modified UTF-8 format for Strings.

## Login protocol
```
Client     Server
|               |
|     Login     |
| >>>>>>>>>>>>> |
|               |
| SyncMappings  |
| <<<<<<<<<<<<< |
|               |
| ConfirmChange |
| >>>>>>>>>>>>> |
```
1. On connect, the client sends a login packet to the server. This allows the server to test the validity of the client,
   as well as allowing the client to declare metadata about itself, such as the username.
1. After validating the login packet, the server sends all its mappings to the client, and the client will apply them.
1. Upon receiving the mappings, the client sends a `ConfirmChange` packet with `sync_id` set to 0, to confirm that it
   has received the mappings and is in sync with the server. Once the server receives this packet, the client will be
   allowed to modify mappings.

The server will not accept any other packets from the client until this entire exchange has been completed. 

## Kicking clients
When the server kicks a client, it may optionally send a `Kick` packet immediately before closing the connection, which
contains the reason why the client was kicked (so the client can display it to the user). This is not required though -
the server may simply terminate the connection.

## Changing mappings
This section uses the example of renaming, but the same pattern applies to all mapping changes.
```
Client A   Server    Client B
|           |               |
| RenameC2S |               |
| >>>>>>>>> |               |
|           |               |
|           |   RenameS2C   |
|           | >>>>>>>>>>>>> |
|           |               |
|           | ConfirmChange |
|           | <<<<<<<<<<<<< |
```

1. Client A validates the name and updates the mapping client-side to give the impression there is no latency >:)
1. Client A sends a rename packet to the server, notifying it of the rename.
1. The server assesses the validity of the rename. If it is invalid for whatever reason (e.g. the mapping was locked or
   the name contains invalid characters), then the server sends an appropriate packet back to client A to revert the
   change, with `sync_id` set to 0. The server will ignore any `ConfirmChange` packets it receives in response to this.
1. If the rename was valid, the server will lock all clients except client A from being able to modify this mapping, and
   then send an appropriate packet to all clients except client A notifying them of this rename. The `sync_id` will be a
   unique non-zero value identifying this change.
1. Each client responds to this packet by updating their mappings locally to reflect this change, then sending a
   `ConfirmChange` packet with the same `sync_id` as the one in the packet they received, to confirm that they have
   received the change.
1. When the server receives the `ConfirmChange` packet, and another change to that mapping hasn't occurred since, the
   server will unlock that mapping for that client and allow them to make changes again.

## Packets
```c
struct Packet {
    unsigned short packet_id;
    data[]; // depends on packet_id
}
```
The IDs for client-to-server packets are as follows:
- 0: `Login`
- 1: `ConfirmChange`
- 2: `Rename`
- 3: `RemoveMapping`
- 4: `ChangeDocs`
- 5: `MarkDeobfuscated`

The IDs for server-to-client packets are as follows:
- 0: `Kick`
- 1: `SyncMappings`
- 2: `Rename`
- 3: `RemoveMapping`
- 4: `ChangeDocs`
- 5: `MarkDeobfuscated`

### The Entry struct
```c
enum EntryType {
    ENTRY_CLASS = 0, ENTRY_FIELD = 1, ENTRY_METHOD = 2, ENTRY_LOCAL_VAR = 3;
}
struct Entry {
    unsigned byte type;
    boolean has_parent;
    if<has_parent> {
        Entry parent;
    }
    utf name;
    boolean has_javadoc;
    if<has_javadoc> {
        utf javadoc;
    }
    if<type == ENTRY_FIELD || type == ENTRY_METHOD> {
        utf descriptor;
    }
    if<type == ENTRY_LOCAL_VAR> {
        unsigned short index;
        boolean parameter;
    }
}
```
- `type`: The type of entry this is. One of `ENTRY_CLASS`, `ENTRY_FIELD`, `ENTRY_METHOD` or `ENTRY_LOCAL_VAR`.
- `parent`: The parent entry. Only class entries may have no parent. fields, methods and inner classes must have their
            containing class as their parent. Local variables have a method as a parent.
- `name`: The class/field/method/variable name.
- `javadoc`: The javadoc of an entry, if present.
- `descriptor`: The field/method descriptor.
- `index`: The index of the local variable in the local variable table.
- `parameter`: Whether the local variable is a parameter.

### Login (client-to-server)
```c
struct LoginC2SPacket {
    unsigned short protocol_version;
    byte checksum[20];
    utf username;
}
```
- `protocol_version`: the version of the protocol. If the version does not match on the server, then the client will be
                      kicked immediately. Currently always equal to 0.
- `checksum`: the SHA-1 hash of the JAR file the client has open. If this does not match the SHA-1 hash of the JAR file
              the server has open, the client will be kicked.
- `username`: the username of the user logging in. If the username is not unique, the client will be kicked.

### ConfirmChange (client-to-server)
```c
struct ConfirmChangeC2SPacket {
    unsigned short sync_id;
}
```
- `sync_id`: the sync ID to confirm.

### Rename (client-to-server)
```c
struct RenameC2SPacket {
    Entry obf_entry;
    utf new_name;
    boolean refresh_class_tree;
}
```
- `obf_entry`: the obfuscated name and descriptor of the entry to rename.
- `new_name`: what to rename the entry to.
- `refresh_class_tree`: whether the class tree on the sidebar of Enigma needs refreshing as a result of this change.

### RemoveMapping (client-to-server)
```c
struct RemoveMappingC2SPacket {
    Entry obf_entry;
}
```
- `obf_entry`: the obfuscated name and descriptor of the entry to remove the mapping for.

### ChangeDocs (client-to-server)
```c
struct ChangeDocsC2SPacket {
    Entry obf_entry;
    utf new_docs;
}
```
- `obf_entry`: the obfuscated name and descriptor of the entry to change the documentation for.
- `new_docs`: the new documentation for this entry, or an empty string to remove the documentation.

### MarkDeobfuscated (client-to-server)
```c
struct MarkDeobfuscatedC2SPacket {
    Entry obf_entry;
}
```
- `obf_entry`: the obfuscated name and descriptor of the entry to mark as deobfuscated.

### Kick (server-to-client)
```c
struct KickS2CPacket {
    utf reason;
}
```
- `reason`: the reason for the kick, may or may not be a translation key for the client to display to the user.

### SyncMappings (server-to-client)
```c
struct SyncMappingsS2CPacket {
    int num_roots;
    MappingNode roots[num_roots];
}
struct MappingNode {
    NoParentEntry obf_entry;
    boolean is_named;
    if<is_named> {
        utf name;
        boolean has_javadoc;
        if<has_javadoc> {
            utf javadoc;
        }
    }
    unsigned short children_count;
    MappingNode children[children_count];
}
typedef { Entry but without the has_parent or parent fields } NoParentEntry;
```
- `roots`: The root mapping nodes, containing all the entries without parents.
- `obf_entry`: The value of a node, containing the obfuscated name and descriptor of the entry.
- `name`: The deobfuscated name of the entry, if it has a mapping.
- `javadoc`: The documentation for the entry, if it is named and has documentation.
- `children`: The children of this node

### Rename (server-to-client)
```c
struct RenameS2CPacket {
    unsigned short sync_id;
    Entry obf_entry;
    utf new_name;
    boolean refresh_class_tree;
}
```
- `sync_id`: the sync ID of the change for locking purposes.
- `obf_entry`: the obfuscated name and descriptor of the entry to rename.
- `new_name`: what to rename the entry to.
- `refresh_class_tree`: whether the class tree on the sidebar of Enigma needs refreshing as a result of this change.

### RemoveMapping (server-to-client)
```c
struct RemoveMappingS2CPacket {
    unsigned short sync_id;
    Entry obf_entry;
}
```
- `sync_id`: the sync ID of the change for locking purposes.
- `obf_entry`: the obfuscated name and descriptor of the entry to remove the mapping for.

### ChangeDocs (server-to-client)
```c
struct ChangeDocsS2CPacket {
    unsigned short sync_id;
    Entry obf_entry;
    utf new_docs;
}
```
- `sync_id`: the sync ID of the change for locking purposes.
- `obf_entry`: the obfuscated name and descriptor of the entry to change the documentation for.
- `new_docs`: the new documentation for this entry, or an empty string to remove the documentation.

### MarkDeobfuscated (server-to-client)
```c
struct MarkDeobfuscatedS2CPacket {
    unsigned short sync_id;
    Entry obf_entry;
}
```
- `sync_id`: the sync ID of the change for locking purposes.
- `obf_entry`: the obfuscated name and descriptor of the entry to mark as deobfuscated.
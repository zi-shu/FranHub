package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

/**
 * message_node 的索引从 (conversation_id) 换成 (conversation_id, node_index)。
 *
 * 加载会话时按 node_index 排序分页读取，只有 conversation_id 索引时每一页都要把整个会话的行取出来排序，
 * 耗时随消息数平方增长，几千条消息的会话要好几秒才能打开。
 * 复合索引以 conversation_id 开头，外键级联删除和按会话的查询照样能用，旧索引不再需要。
 * 只建索引、删索引，不改动表里的数据。
 *
 * 源自上游 3.3.0 的 Migration_32_33，FranHub 侧编号错开为 33 -> 34。
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (message_node index)")
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

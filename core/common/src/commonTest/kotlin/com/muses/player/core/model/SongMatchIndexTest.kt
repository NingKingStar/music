package com.muses.player.core.model

import kotlin.test.*

class SongMatchIndexTest {
    private fun song(id:String,title:String,artist:String?)=Song(id,"test","/$id",title,artist)
    @Test fun `索引保留原先首个匹配别名版本和空歌手规则`() {
        val saved=listOf(song("a","歌曲","周杰伦(Jay Chou)"),song("b","歌曲","Jay Chou"),song("c","歌曲 Live","Jay Chou"),song("d","另一个标题",null))
        val probes=listOf(song("x","歌曲","Jay Chou"),song("d","随意",null),song("z","歌曲 Live","Jay Chou"),song("w","歌曲",null),song("n","歌曲","另一歌手"))
        val index=SongMatchIndex(saved)
        probes.forEach { assertEquals(saved.firstOrNull { entry->sameSong(entry,it) },index.find(it)) }
    }
    @Test fun `大量无关歌曲不会改变首个匹配结果`() {
        val saved=(0..1999).map { song("$it","歌$it","歌手") }
        assertEquals(saved[1777],SongMatchIndex(saved).find(song("online","歌1777","歌手")))
    }
}

package com.muses.player.core.search

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class SearchRequestSchedulingTest {
    @Test fun `同平台不同关键词不被整个平台锁阻塞`() = runTest {
        val started=CompletableDeferred<Unit>(); val finish=CompletableDeferred<Unit>()
        val provider=object: OnlineSearchProvider {
            override val platform="wy"; override val displayName="测试"
            override suspend fun search(keyword:String,page:Int,pageSize:Int):OnlineSearchPage {
                if(keyword=="background"){started.complete(Unit);finish.await()}
                return OnlineSearchPage(platform,keyword,page,emptyList(),false)
            }
        }
        val service=OnlineSearchService(listOf(provider))
        val background=async { service.search("wy","background",background=true) }; started.await()
        val front=async { service.search("wy","front") }; runCurrent()
        assertTrue(front.isCompleted,"用户搜索不应等待后台不同关键词请求")
        finish.complete(Unit); background.await(); front.await()
    }
    @Test fun `相同查询并发成功只请求一次且取消不锁死`() = runTest {
        var calls=0
        val provider=object:OnlineSearchProvider {
            override val platform="wy"; override val displayName="测试"
            override suspend fun search(keyword:String,page:Int,pageSize:Int):OnlineSearchPage {
                calls++;delay(100);return OnlineSearchPage(platform,keyword,page,emptyList(),false)
            }
        }
        val service=OnlineSearchService(listOf(provider))
        val jobs=(1..5).map { async { service.search("wy","same") } }; jobs.awaitAll()
        assertEquals(1,calls)
        val job=launch { service.search("wy","cancel") };runCurrent();job.cancelAndJoin()
        service.search("wy","cancel");assertEquals(3,calls)
    }
    @Test fun `后台搜索最多占一个并发位前台仍能请求`() = runTest {
        var active=0;var max=0;val finish=CompletableDeferred<Unit>()
        val provider=object:OnlineSearchProvider {
            override val platform="wy";override val displayName="测试"
            override suspend fun search(keyword:String,page:Int,pageSize:Int):OnlineSearchPage {
                if(keyword.startsWith("bg")){ active++;max=maxOf(max,active);finish.await();active-- }
                return OnlineSearchPage(platform,keyword,page,emptyList(),false)
            }
        }
        val service=OnlineSearchService(listOf(provider))
        val bg=(1..4).map { async { service.search("wy","bg$it",background=true) } };runCurrent()
        val front=async { service.search("wy","front") };runCurrent();assertTrue(front.isCompleted);assertEquals(1,max)
        finish.complete(Unit);bg.awaitAll();front.await()
    }
}

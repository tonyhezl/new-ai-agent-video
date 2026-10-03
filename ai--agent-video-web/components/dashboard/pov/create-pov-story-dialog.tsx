"use client";

import { useState, useRef } from "react";
import { useRouter } from "next/navigation";
import {
  X,
  Sparkles,
  Loader2,
  Eye,
  Mic,
  Volume2,
  Flame,
  CheckCircle2,
  Dices,
  Save,
  Zap,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { povStoryApi } from "@/lib/api/pov-story";
import { API_BASE_URL } from "@/lib/api/client";
import { authenticatedFetch } from "@/lib/api/ai-assistant";
import { POV_VOICE_OPTIONS } from "@/lib/pov/voice-options";
import { toast } from "sonner";

interface CreatePovStoryDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated?: (storyId: number) => void;
}

export const GENRE_OPTIONS = [
  {
    key: "URBAN_MYSTERY",
    label: "都市怪谈",
    desc: "独居异响、电梯怪事、午夜监控反常、楼道空心墙",
    color: "from-purple-500/20 to-indigo-500/20 border-purple-500/40 text-purple-300",
  },
  {
    key: "DEEP_EXPLORATION",
    label: "废墟探险",
    desc: "百年老宅暗格、荒废防空洞、地下密室、旧录像带",
    color: "from-amber-500/20 to-orange-500/20 border-amber-500/40 text-amber-300",
  },
  {
    key: "HAUNTED_HOUSE_TESTER",
    label: "凶宅试睡",
    desc: "试睡员记录、红外夜视仪反常、深夜门缝黑影、温度骤降",
    color: "from-red-500/20 to-rose-500/20 border-red-500/40 text-red-300",
  },
  {
    key: "OCCUPATIONAL_TABOO",
    label: "特殊职业",
    desc: "遗物整理师、火葬场夜班守则、古董修复怪事、深海潜水",
    color: "from-emerald-500/20 to-teal-500/20 border-emerald-500/40 text-emerald-300",
  },
  {
    key: "TECH_PARANOIA",
    label: "科技惊悚",
    desc: "智能猫眼深夜报警、AI伴侣神秘代码、匿名暗网包裹",
    color: "from-indigo-500/20 to-blue-500/20 border-indigo-500/40 text-indigo-300",
  },
  {
    key: "UNEXPLORED_ZONE",
    label: "诡异禁区",
    desc: "无人区罗盘失灵、深山林场日志、古老废井回声、雪山脚印",
    color: "from-blue-500/20 to-indigo-500/20 border-blue-500/40 text-blue-300",
  },
  {
    key: "ANOMALOUS_SPACE",
    label: "曼德拉异象",
    desc: "熟悉的家细微变化、不存在的楼层、镜子延迟半秒、多出的人",
    color: "from-pink-500/20 to-pink-500/20 border-pink-500/40 text-pink-300",
  },
  {
    key: "FOLKLORE_SECRETS",
    label: "民间秘辛",
    desc: "带锁泛黄日记、当铺绝当红木匣、老宅地窖封条、神秘古镜",
    color: "from-yellow-500/20 to-amber-500/20 border-yellow-500/40 text-yellow-300",
  },
];

const INSPIRATION_PRESETS: Record<string, string[]> = {
  URBAN_MYSTERY: [
    "独居深夜12点，天花板总是传来弹珠滚落声，直到今天我把手电筒和摄像头塞进了通风管道...",
    "老旧小区的电梯总是自动停在不存在的负一层，今天我决定留在里面看看到底发生了什么...",
    "刚搬进合租房，发现卫生间的镜子背后是空心的，拆开后看到墙里嵌着一部正在录像的手机...",
    "深夜楼下便利店的自动门总是隔五分钟感应开启一次，但监控画面里门前空无一人...",
  ],
  DEEP_EXPLORATION: [
    "在百年老宅清理废品，手电筒光晃到了衣柜夹层暗格，里面有一本用封条封死的未拆封信件...",
    "进入荒废了30年的地下防空洞，发现最深处的铁门被从外面反锁，门后还留着新鲜的抓痕...",
    "在二手跳蚤市场淘了一台老式DV摄像机，插上电后发现里面最后一段未删除的录像正拍着我的后背...",
    "探访山顶废弃气象观测站，电台突然自动连通，里面传来一段20年前失踪科考队的声音...",
  ],
  HAUNTED_HOUSE_TESTER: [
    "我是凶宅试睡员，深夜两点戴上红外夜视仪，发现正前方的空沙发上浮现出一团清晰的人形热源...",
    "凶宅试睡第四个小时，随身携带的测温枪突然从24度瞬间骤降至零下3度，浴室水龙头自动打开...",
    "在发生过意外的老公寓试睡，半夜智能手环连续报警心率异常，手环同步定位显示房间里有两个人...",
    "给凶宅客厅架设了全景360度摄像头，凌晨3点手机App突然疯狂推送【画面中央检测到微笑人脸】...",
  ],
  OCCUPATIONAL_TABOO: [
    "我是一名遗物整理师，今天受委托清理一位孤寡老人的房间，在他的床底发现了一个带密码锁的保温箱...",
    "火葬场夜班值班第100天，火化炉温度传感器突然报警，显示炉内温度异常且重力感应正在增加...",
    "古董修复师记录：修补明代红木梳妆匣时，夹层掉出一束用红绳系着的头发和一张生辰八字...",
    "深海潜水管道检修员，潜到水下80米昏暗水域时，头灯照见废弃沉船窗口里伸出了一只戴着现代手表的手...",
  ],
  TECH_PARANOIA: [
    "家里的智能猫眼突然在凌晨3点疯狂报警，点开实时监控，门外猫眼镜头前贴着一只睁大的眼睛...",
    "新买的AI陪伴音箱半夜突然自己说话：'你床底下太挤了，能让我换个地方吗？'...",
    "收到一个没有寄件人信息的暗网神秘包裹，拆开里面是一枚U盘，插上电脑显示的是我现在的背影直播...",
    "云相册突然自动备份了一张我熟睡时的俯拍照片，但我是独居且手机一直放在床头柜充电...",
  ],
  UNEXPLORED_ZONE: [
    "深山林场护林员巡山日志：指南针在黑松林边缘疯狂旋转，林间迷雾里矗立着一栋现代风格的空别墅...",
    "跟随地质勘探队进入无人区，在峡谷深处发现了一口垂直向下的古老青铜井，井底每隔十分钟传来呼吸声...",
    "雪山徒步穿越时突遇暴风雪，顺着雪地上一串反向的巨大赤足脚印，我找到了一个发光的冰洞入口...",
    "夜间驾车穿越无人戈壁，导航突然播报：'前方500米请掉头，您已驶离地球表面'...",
  ],
  ANOMALOUS_SPACE: [
    "出差回家推开门，屋里的所有家具摆件看起来都一样，但全部比以前小了十分之一...",
    "公寓楼总共12层，今天电梯按键上却赫然亮着一个红色的13层，门缓缓打开了...",
    "洗完脸抬头看镜子，发现镜子里的倒影比我的真实动作慢了整整半秒钟...",
    "整理家族老相册，发现每一张三十年前的全家福背景角落里，都站着同一个穿着灰色连帽衫的人...",
  ],
  FOLKLORE_SECRETS: [
    "老宅翻修挖出了一坛密封百年的陈年老酒，敲开泥封后里面飘出的不是酒香，而是一股檀香味...",
    "爷爷去世留下一把生锈的铜钥匙，嘱咐千万不能打开东屋阁楼的暗箱，今天钥匙突然发热了...",
    "在古镇当铺收到一块清代的黑玉镯，只要放在耳边，就能听到断断续续的求救声...",
    "老宅地窖深处有一道贴满黄符的石门，今天早上下去拿红薯，发现符纸全被从里面撕碎了...",
  ],
};

const BGM_OPTIONS = [
  { id: "SUSPENSE_HEARTBEAT", name: "心跳加速 + 低频压迫" },
  { id: "RAINY_NIGHT", name: "雨夜窗外白噪音 + 远处雷鸣" },
  { id: "DARK_AMBIENT", name: "暗黑空间回响 + 电磁干扰" },
];

export function CreatePovStoryDialog({
  open,
  onClose,
  onCreated,
}: CreatePovStoryDialogProps) {
  const router = useRouter();
  const [genre, setGenre] = useState("URBAN_MYSTERY");
  const [topic, setTopic] = useState("");
  const [voiceId, setVoiceId] = useState(POV_VOICE_OPTIONS[0].id);
  const [bgmStyle, setBgmStyle] = useState(BGM_OPTIONS[0].id);

  // 流式生成状态
  const [isGenerating, setIsGenerating] = useState(false);
  const [streamContent, setStreamContent] = useState("");
  const [reasoningContent, setReasoningContent] = useState("");
  const [saving, setSaving] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);

  // 随机换一个灵感
  const handleRandomInspiration = (targetGenre = genre) => {
    const list = INSPIRATION_PRESETS[targetGenre] || INSPIRATION_PRESETS.URBAN_MYSTERY;
    const rand = list[Math.floor(Math.random() * list.length)];
    setTopic(rand);
  };

  const handleSelectGenre = (newGenre: string) => {
    setGenre(newGenre);
    const list = INSPIRATION_PRESETS[newGenre] || [];
    if (list.length > 0) {
      setTopic(list[Math.floor(Math.random() * list.length)]);
    }
  };

  const handleStartGenerate = async () => {
    setIsGenerating(true);
    setStreamContent("");
    setReasoningContent("");

    const controller = new AbortController();
    abortControllerRef.current = controller;

    try {
      const response = await authenticatedFetch(
        `${API_BASE_URL}/api/pov/stories/generate-stream`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            topic: topic.trim() || undefined,
            topicGenre: genre,
            voiceId,
            bgmStyle,
          }),
          signal: controller.signal,
        }
      );

      if (!response.ok) {
        throw new Error(`请求失败: HTTP ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error("无法读取流式响应");

      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed.startsWith("data:")) {
            const dataStr = trimmed.slice(5).trim();
            if (!dataStr) continue;

            try {
              const event = JSON.parse(dataStr);
              if (event.outputType === "REASONING" && event.reasoningContent) {
                setReasoningContent((prev) => prev + event.reasoningContent);
              } else if (event.outputType === "CONTENT" && event.content) {
                setStreamContent((prev) => prev + event.content);
              }
            } catch {
              // 忽略解析单行错误
            }
          }
        }
      }
      toast.success("第一人称猎奇剧本生成完毕！");
    } catch (error: unknown) {
      if (!(error instanceof Error && error.name === "AbortError")) {
        toast.error(`生成失败: ${error instanceof Error ? error.message : "网络异常"}`);
      }
    } finally {
      setIsGenerating(false);
    }
  };

  const handleSave = async () => {
    if (!streamContent.trim()) {
      toast.error("暂无生成的剧本内容");
      return;
    }

    setSaving(true);
    try {
      const savedStory = await povStoryApi.saveFromAi({
        aiOutputText: streamContent,
        voiceId,
        bgmStyle,
      });

      toast.success("故事已成功保存并进入分镜工作台");
      onClose();
      if (onCreated) {
        onCreated(savedStory.id);
      } else {
        router.push(`/pov-stories/${savedStory.id}`);
      }
    } catch (error: unknown) {
      toast.error(`保存失败: ${error instanceof Error ? error.message : "未知错误"}`);
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    setIsGenerating(false);
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-4xl max-h-[92vh] flex flex-col bg-card/95 border border-border/60 rounded-2xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-border/40 bg-muted/20">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-400">
              <Eye className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-semibold tracking-tight text-foreground flex items-center gap-2">
                第一人称（POV）猎奇故事全自动生成
                <span className="text-xs px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-400 border border-amber-500/30 font-medium">
                  一键全自动
                </span>
              </h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                选择心仪品类，AI 即刻全自动构思爆款悬疑反转剧情并拆解为 6~8 镜沉浸式视角
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-foreground/5 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          {!streamContent && !isGenerating ? (
            <>
              {/* 题材选择 (8 大垂直细分品类) */}
              <div className="space-y-2.5">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-semibold text-foreground flex items-center gap-1.5">
                    <Flame className="w-4 h-4 text-amber-400" />
                    选择猎奇题材类型（点击即换爆款预设）
                  </label>
                  <span className="text-xs text-amber-400 font-mono">
                    8 大热门爆款垂直品类
                  </span>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                  {GENRE_OPTIONS.map((g) => (
                    <div
                      key={g.key}
                      onClick={() => handleSelectGenre(g.key)}
                      className={cn(
                        "p-3 rounded-xl border cursor-pointer transition-all relative flex flex-col justify-between",
                        genre === g.key
                          ? cn("bg-gradient-to-br shadow-md ring-1 ring-amber-400/50", g.color)
                          : "bg-card hover:bg-muted/30 border-border/40 text-muted-foreground hover:border-border"
                      )}
                    >
                      <div>
                        <div className="flex items-center justify-between">
                          <span className="font-semibold text-xs text-foreground">
                            {g.label}
                          </span>
                          {genre === g.key && (
                            <CheckCircle2 className="w-3.5 h-3.5 text-amber-400" />
                          )}
                        </div>
                        <p className="text-[11px] text-muted-foreground mt-1 line-clamp-2 leading-relaxed">
                          {g.desc}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* 故事灵感 / 窥探主题（全自动可选输入） */}
              <div className="space-y-2 p-4 rounded-xl bg-muted/20 border border-border/40">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                    <Zap className="w-3.5 h-3.5 text-amber-400" />
                    故事灵感 / 窥探主题 (已自动填充，可自由修改或直接生成)
                  </label>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => handleRandomInspiration()}
                      className="flex items-center gap-1 text-[11px] px-2.5 py-1 rounded-lg bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 border border-amber-500/30 transition-colors"
                      title="随机切换当前品类的爆款灵感"
                    >
                      <Dices className="w-3.5 h-3.5" />
                      🎲 换一个灵感
                    </button>
                    {topic && (
                      <button
                        type="button"
                        onClick={() => setTopic("")}
                        className="text-[11px] text-muted-foreground hover:text-foreground px-2 py-1"
                      >
                        清空(纯AI自主选题)
                      </button>
                    )}
                  </div>
                </div>

                <textarea
                  value={topic}
                  onChange={(e) => setTopic(e.target.value)}
                  placeholder="💡 AI 已准备好！即使完全留空，系统也会依据所选题材由 AI 全自动自主构思最惊险的爆款剧情..."
                  className="w-full h-24 px-3.5 py-2.5 rounded-xl bg-background/60 border border-border/50 text-xs focus:outline-none focus:ring-2 focus:ring-amber-400/30 resize-none placeholder:text-muted-foreground/60 leading-relaxed"
                />
              </div>

              {/* 配音与音效配置 */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-1.5">
                  <label className="text-xs font-medium text-muted-foreground flex items-center gap-1.5">
                    <Mic className="w-3.5 h-3.5 text-blue-400" />
                    第一人称沉浸音色
                  </label>
                  <select
                    value={voiceId}
                    onChange={(e) => setVoiceId(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg bg-muted/40 border border-border/50 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                  >
                    {POV_VOICE_OPTIONS.map((v) => (
                      <option key={v.id} value={v.id}>
                        {v.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-medium text-muted-foreground flex items-center gap-1.5">
                    <Volume2 className="w-3.5 h-3.5 text-emerald-400" />
                    环境氛围音效
                  </label>
                  <select
                    value={bgmStyle}
                    onChange={(e) => setBgmStyle(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg bg-muted/40 border border-border/50 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                  >
                    {BGM_OPTIONS.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.name}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </>
          ) : (
            /* 流式生成展示区 */
            <div className="space-y-4">
              {/* 深度思考推理折叠 */}
              {reasoningContent && (
                <div className="p-3.5 rounded-xl bg-amber-500/5 border border-amber-500/20 text-xs text-amber-300/80 space-y-1">
                  <div className="font-semibold flex items-center gap-1.5 text-amber-400">
                    <Sparkles className="w-3.5 h-3.5 animate-spin" />
                    Agent 深度推理与悬疑结构构思中...
                  </div>
                  <div className="font-mono text-[11px] leading-relaxed whitespace-pre-wrap max-h-32 overflow-y-auto">
                    {reasoningContent}
                  </div>
                </div>
              )}

              {/* 正文 JSON / 文本打字机 */}
              <div className="p-4 rounded-xl bg-muted/30 border border-border/60">
                <div className="flex items-center justify-between pb-2 border-b border-border/30 text-xs text-muted-foreground">
                  <span className="font-medium text-foreground flex items-center gap-1.5">
                    <Eye className="w-3.5 h-3.5 text-amber-400" />
                    第一人称猎奇分镜生成结果
                  </span>
                  {isGenerating && (
                    <span className="flex items-center gap-1 text-amber-400 animate-pulse">
                      <Loader2 className="w-3 h-3 animate-spin" />
                      正在实时生成...
                    </span>
                  )}
                </div>
                <pre className="mt-3 text-xs font-mono text-foreground/90 whitespace-pre-wrap overflow-x-auto max-h-96 leading-relaxed">
                  {streamContent || "等待 Agent 吐字..."}
                </pre>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-6 py-4 border-t border-border/40 bg-muted/20">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-muted-foreground hover:text-foreground rounded-lg transition-colors"
          >
            取消
          </button>

          <div className="flex items-center gap-3">
            {isGenerating ? (
              <button
                onClick={handleCancel}
                className="px-4 py-2 text-xs font-medium text-rose-400 bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 rounded-xl transition-colors"
              >
                停止生成
              </button>
            ) : streamContent ? (
              <>
                <button
                  onClick={() => {
                    setStreamContent("");
                    setReasoningContent("");
                  }}
                  className="px-4 py-2 text-xs font-medium text-muted-foreground hover:text-foreground rounded-xl transition-colors"
                >
                  重新配置
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex items-center gap-2 px-5 py-2 text-xs font-medium text-black bg-amber-400 hover:bg-amber-300 rounded-xl shadow-md transition-all disabled:opacity-50"
                >
                  {saving ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Save className="w-3.5 h-3.5" />
                  )}
                  保存并进入分镜工作台
                </button>
              </>
            ) : (
              <button
                onClick={handleStartGenerate}
                className="flex items-center gap-2 px-6 py-2.5 text-xs font-semibold text-black bg-gradient-to-r from-amber-400 to-orange-400 hover:from-amber-300 hover:to-orange-300 rounded-xl shadow-lg shadow-amber-500/20 transition-all hover:scale-[1.02] active:scale-[0.98]"
              >
                <Sparkles className="w-4 h-4" />
                🚀 全自动生成爆款故事
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

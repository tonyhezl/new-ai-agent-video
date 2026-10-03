import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "第一人称猎奇故事 | AI Agent Video",
  description: "全自动生成高完播率第一人称（POV）短视频与沉浸式悬疑故事",
};

export default function PovStoriesLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <div className="w-full h-full min-h-0">{children}</div>;
}

import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "./MdRender.css";

interface MdRenderProps {
  content: string;
}

/**
 * Markdown 渲染组件。
 * 支持 GFM 标准（表格、任务列表、删除线、脚注等）。
 * 用于渲染 AI 回复（可能带 Markdown 语法）。
 */
const MdRender: React.FC<MdRenderProps> = ({ content }) => {
  return (
    <div className="md-container">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          table: ({ children }) => <table className="md-table">{children}</table>,
          thead: ({ children }) => <thead className="md-table-head">{children}</thead>,
          th: ({ children }) => <th className="md-table-th">{children}</th>,
          td: ({ children }) => <td className="md-table-td">{children}</td>,
          tr: ({ children }) => <tr className="md-table-row">{children}</tr>,
          ul: ({ children }) => <ul className="md-ul">{children}</ul>,
          ol: ({ children }) => <ol className="md-ol">{children}</ol>,
          li: ({ children }) => <li className="md-li">{children}</li>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default MdRender;

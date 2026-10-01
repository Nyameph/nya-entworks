package io.github.Nyameph.nyaentworks.manga.util;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

import java.util.*;
import java.util.stream.Collectors;

public class BracketParser {

    @AllArgsConstructor
    @Getter
    public enum BracketType {
        PARENTHESIS('(', ')'),
        BRACKET('[', ']'),
        BRACKET_CN('【', '】');

        private final Character start;
        private final Character end;

        public static BracketType matchStart(char c) {
            for (BracketType value : values()) {
                if (value.start == c) {
                    return value;
                }
            }
            return null;
        }

        public static BracketType matchEnd(char c) {
            for (BracketType value : values()) {
                if (value.end == c) {
                    return value;
                }
            }
            return null;
        }
    }
    public enum Position { FRONT, TAIL, MIDDLE }

    @Data
    public static class Node implements Cloneable{
        private String text;
        private BracketType bracketType;
        private Position position;
        private List<Node> children;
        private boolean used;
        /** 本节点与其首个子节点之间在原串中是否有空格（含被 trim 消除的空格） */
        private boolean hasSpaceWithChild;
        /** 本节点与其同级下一个节点之间在原串中是否有空格（含被 trim 消除的空格） */
        private boolean hasSpaceWithNext;

        public Node() {
            this.children = new ArrayList<>();
            this.bracketType = null;
        }

        @Override
        public String toString(){
            return toString("");
        }
        private String toString(String prefix) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append("Node{text=").append(text).append(", bracketType=").append(bracketType).append(", position=").append(position);
            if(CollectionUtil.isNotEmpty(children)) {
                sb.append(", children=[\n");
                children.forEach(child -> sb.append(prefix).append(child.toString("  ")).append("\n"));
                sb.append(prefix).append("]");
            }
            sb.append("}");
            return sb.toString();
        }

        public String toContentString() {
            StringBuilder sb = new StringBuilder(text);
            if(CollectionUtil.isNotEmpty(children)) {
                if (hasSpaceWithChild) {
                    sb.append(" ");
                }
                for (Node child : children) {
                    sb.append(child.toSimpleString());
                    if (child.hasSpaceWithNext) {
                        sb.append(" ");
                    }
                }
            }
            return sb.toString();
        }

        public  String toSimpleString() {
            StringBuilder sb = new StringBuilder();
            if(bracketType != null) {
                sb.append(bracketType.getStart());
            }
            sb.append(this.toContentString());
            if(bracketType != null) {
                sb.append(bracketType.getEnd());
            }
            return sb.toString();
        }

        @Override
        public Node clone() {
            try {
                Node clone = (Node) super.clone();
                clone.setText(this.text);
                clone.setBracketType(this.bracketType);
                clone.setPosition(this.position);
                clone.setChildren(this.children.stream().map(Node::clone).toList());
                clone.setUsed(this.used);
                clone.setHasSpaceWithChild(this.hasSpaceWithChild);
                clone.setHasSpaceWithNext(this.hasSpaceWithNext);
                return clone;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError();
            }
        }
    }

    /**
     * 将字符串解析为多个树形 {@link Node}。
     * <p>规则概述：
     * <ul>
     *     <li>括号需成对匹配；遇到无法匹配的括号则视为普通字符，直到遇到能匹配的括号或字符串结束。</li>
     *     <li>括号节点的 {@code text} 为从左括号起、到第一个子节点（或右括号）之间的无括号内容。</li>
     *     <li>{@code position}：从头部/尾部连续数括号组，直到出现中间无括号（非空格）内容为止分别记为
     *         {@link Position#FRONT}/{@link Position#TAIL}；其余为 {@link Position#MIDDLE}。
     *         若整层没有中间无括号内容，则每个括号组按更靠近头部或尾部（即最顶层括号序号）判定。</li>
     * </ul>
     *
     * @param str 原始字符串
     * @return 顶层节点列表；{@code str} 为空时返回空列表
     */
    public static List<Node> parseStr(String str) {
        if (str == null || str.isEmpty()) {
            return new ArrayList<>();
        }
        return parse(str, 0, str.length());
    }

    /**
     * 解析 {@code s} 的 {@code [start, end)} 区间，直接产出本层 {@link Node} 列表。
     * <p>无括号文本段与成对括号组统一用 {@link Node} 表示（文本段的
     * {@code bracketType} 为 null；无法匹配的括号并入文本段。
     * 括号组递归解析内部内容，并把内部起始的文本节点吸收为自身的 {@code text}。
     */
    private static List<Node> parse(String s, int start, int end) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = start;
        while (i < end) {
            char c = s.charAt(i);
            BracketType open = BracketType.matchStart(c);
            if (open != null) {
                int match = findMatch(s, i, end);
                if (match != -1) {
                    flushText(nodes, buf);
                    Node node = new Node();
                    node.setBracketType(open);
                    List<Node> inner = parse(s, i + 1, match);
                    // 左括号到第一个子节点之间的无括号内容作为本节点文本；
                    // hasSpaceWithChild 表示本文本与首个子节点之间是否有空格
                    if (!inner.isEmpty() && inner.get(0).getBracketType() == null) {
                        // 有头部文本：继承其尾部空格（文本与首个子节点之间的空格）
                        Node absorbed = inner.remove(0);
                        node.setText(absorbed.getText());
                        node.setHasSpaceWithChild(absorbed.isHasSpaceWithNext());
                    } else {
                        // 无头部文本：左括号后紧跟子节点，判断二者之间是否有空格
                        node.setText("");
                        node.setHasSpaceWithChild(i + 1 < match && Character.isWhitespace(s.charAt(i + 1)));
                    }
                    node.setChildren(inner);
                    nodes.add(node);
                    i = match + 1;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flushText(nodes, buf);
        // 末尾节点没有同级下一个节点，hasSpaceWithNext 恒为 false
        if (!nodes.isEmpty()) {
            nodes.get(nodes.size() - 1).setHasSpaceWithNext(false);
        }
        assignPositions(nodes);
        return nodes;
    }

    /**
     * 将文本缓冲区 {@code buf} 去除首尾空白后作为无括号 {@link Node} 追加到 {@code nodes}；
     * 纯空白（trim 后为空）不产生节点。追加后清空缓冲区。
     */
    private static void flushText(List<Node> nodes, StringBuilder buf) {
        if (buf.length() == 0) {
            return;
        }
        // buf 首字符为空格 → 上一个兄弟节点与本段之间有空格
        if (!nodes.isEmpty() && Character.isWhitespace(buf.charAt(0))) {
            nodes.get(nodes.size() - 1).setHasSpaceWithNext(true);
        }
        // buf 尾字符为空格 → 本文本节点与其后续节点之间有空格（trim 前记录）
        boolean trailingSpace = Character.isWhitespace(buf.charAt(buf.length() - 1));
        String text = buf.toString().trim();
        buf.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        Node node = new Node();
        node.setBracketType(null);
        node.setText(text);
        node.setHasSpaceWithNext(trailingSpace);
        nodes.add(node);
    }

    /**
     * 为本层节点判定 {@link Position}：文本节点恒为 {@link Position#MIDDLE}，
     * 括号组依据其相对首/末文本节点的位置及顶层序号判定。
     * <p>需在括号组吸收内部起始文本之前调用，以保证被吸收的文本节点仍参与首/末判定。
     */
    public static void assignPositions(List<Node> nodes) {
        int firstText = -1;
        int lastText = -1;
        int groupCount = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.getBracketType() == null) {
                if (firstText == -1) {
                    firstText = i;
                }
                lastText = i;

                while(i < nodes.size() - 1 && nodes.get(i + 1).getBracketType() == null) {
                    // 连续多个无括号部分直接连接成一个
                    node.setText(node.getText() + (node.isHasSpaceWithNext() ? " " : "") + nodes.get(i + 1).getText());
                    node.setHasSpaceWithNext(nodes.get(i + 1).isHasSpaceWithNext());
                    nodes.remove(i + 1);
                }
            } else {
                groupCount++;
            }
        }
        int groupOrder = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.getBracketType() == null) {
                node.setPosition(Position.MIDDLE);
            } else {
                node.setPosition(positionOf(i, groupOrder, groupCount, firstText, lastText));
                groupOrder++;
            }
        }
    }

    /**
     * 在 {@code [openPos + 1, end)} 内寻找 {@code openPos} 处开括号的匹配闭括号下标。
     * <p>采用带外层优先的栈匹配：闭括号优先匹配栈中最近的同类开括号，其上方尚未闭合的
     * 开括号视为普通字符被丢弃；无法与栈中任何开括号匹配的闭括号也视为普通字符。
     *
     * @return 匹配的闭括号下标，找不到返回 {@code -1}
     */
    private static int findMatch(String s, int openPos, int end) {
        Deque<BracketType> stack = new ArrayDeque<>();
        stack.push(BracketType.matchStart(s.charAt(openPos)));
        for (int i = openPos + 1; i < end; i++) {
            char c = s.charAt(i);
            if (BracketType.matchStart(c) != null) {
                stack.push(BracketType.matchStart(c));
                continue;
            }
            BracketType close = BracketType.matchEnd(c);
            if (close == null || !stack.contains(close)) {
                continue; // 无法匹配的闭括号（或普通字符）视为普通字符
            }
            // 弹出栈顶直到并包含匹配的同类开括号，其上方开括号按普通字符丢弃
            while (stack.pop() != close) {
                // 丢弃未匹配的内层开括号
            }
            if (stack.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判定括号组的位置。
     *
     * @param nodeIdx    节点下标
     * @param groupOrder 该括号组在本层括号中的序号（从 0 开始）
     * @param groupCount 本层括号组总数
     * @param firstText  首个文本节点下标，无则 {@code -1}
     * @param lastText   末个文本节点下标，无则 {@code -1}
     */
    private static Position positionOf(int nodeIdx, int groupOrder, int groupCount, int firstText, int lastText) {
        boolean canFront = (firstText == -1) || (nodeIdx < firstText);
        boolean canTail = (lastText == -1) || (nodeIdx > lastText);
        if (canFront && !canTail) {
            return Position.FRONT;
        }
        if (canTail && !canFront) {
            return Position.TAIL;
        }
        if (!canFront && !canTail) {
            return Position.MIDDLE;
        }
        // 整层无中间无括号内容：按更靠近头部/尾部判定，平分时归前部
        return (2 * groupOrder < groupCount) ? Position.FRONT : Position.TAIL;
    }

}
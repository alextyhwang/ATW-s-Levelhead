package com.atw.levelhead.hook;

import com.atw.levelhead.render.TabListFormatter;
import net.weavemc.loader.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class TabListLevelTagHook extends Hook {
    private static final String GUI_PLAYER_TAB_OVERLAY = "net/minecraft/client/gui/GuiPlayerTabOverlay";
    private static final String NETWORK_PLAYER_INFO = "net/minecraft/client/network/NetworkPlayerInfo";
    private static final String TAB_LIST_FORMATTER = Type.getInternalName(TabListFormatter.class);

    public TabListLevelTagHook() {
        super(GUI_PLAYER_TAB_OVERLAY);
    }

    @Override
    public void transform(@NotNull org.objectweb.asm.tree.ClassNode node, @NotNull AssemblerConfig cfg) {
        int installed = 0;
        for (MethodNode method : node.methods) {
            if (!isGetPlayerName(method) || alreadyFormatsTabName(method)) {
                continue;
            }

            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(instruction, buildFormatInstructions());
                    installed++;
                }
            }
        }

        if (installed > 0) {
            cfg.computeFrames();
            System.out.println("[ATW LevelHead] Installed tab list LevelHead hook in " + node.name + ".");
        } else {
            System.out.println("[ATW LevelHead] No tab list LevelHead hook target matched in " + node.name);
        }
    }

    private boolean isGetPlayerName(MethodNode method) {
        return "getPlayerName".equals(method.name)
                && ("(L" + NETWORK_PLAYER_INFO + ";)Ljava/lang/String;").equals(method.desc);
    }

    private boolean alreadyFormatsTabName(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode
                    && TAB_LIST_FORMATTER.equals(((MethodInsnNode) instruction).owner)) {
                return true;
            }
        }
        return false;
    }

    private InsnList buildFormatInstructions() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                TAB_LIST_FORMATTER,
                "appendLevelTag",
                "(Ljava/lang/String;L" + NETWORK_PLAYER_INFO + ";)Ljava/lang/String;",
                false
        ));
        return instructions;
    }
}

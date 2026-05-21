package com.atw.levelhead.hook;

import net.weavemc.loader.api.Hook;
import net.weavemc.loader.api.event.CancellableEvent;
import net.weavemc.loader.api.event.ChatSentEvent;
import net.weavemc.loader.api.event.EventBus;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class ChatCommandCompatibilityHook extends Hook {
    private static final String CHAT_EVENT = Type.getInternalName(ChatSentEvent.class);
    private static final String EVENT_BUS = Type.getInternalName(EventBus.class);
    private static final String EVENT = "net/weavemc/loader/api/event/Event";
    private static final String CANCELLABLE_EVENT = Type.getInternalName(CancellableEvent.class);
    private static final String ENTITY_PLAYER_SP = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";

    public ChatCommandCompatibilityHook() {
        super(ENTITY_PLAYER_SP, GUI_SCREEN);
    }

    @Override
    public void transform(@NotNull org.objectweb.asm.tree.ClassNode node, @NotNull AssemblerConfig cfg) {
        int installed = 0;
        for (MethodNode method : node.methods) {
            if (!isChatSendMethod(node.name, method) || alreadyDispatchesChatEvent(method)) {
                continue;
            }

            method.instructions.insert(buildDispatchInstructions(method));
            cfg.computeFrames();
            installed++;
            System.out.println("[ATW LevelHead] Installed chat command compatibility hook in " + node.name + "." + method.name + method.desc);
        }

        if (installed == 0) {
            System.out.println("[ATW LevelHead] No chat command compatibility hook target matched in " + node.name);
        }
    }

    private boolean isChatSendMethod(String owner, MethodNode method) {
        if (ENTITY_PLAYER_SP.equals(owner)) {
            return "(Ljava/lang/String;)V".equals(method.desc)
                    && (hasMethodCall(method, "sendQueue") || hasMethodCall(method, "addToSendQueue"));
        }

        if (GUI_SCREEN.equals(owner)) {
            return ("(Ljava/lang/String;)V".equals(method.desc) || "(Ljava/lang/String;Z)V".equals(method.desc))
                    && isGuiChatBridge(method);
        }

        return false;
    }

    private boolean hasMethodCall(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && name.equals(((MethodInsnNode) instruction).name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGuiChatBridge(MethodNode method) {
        if ("setClipboardString".equals(method.name)) {
            return false;
        }

        return "sendChatMessage".equals(method.name)
                || "setText".equals(method.name)
                || hasMethodCall(method, "sendChatMessage");
    }

    private boolean alreadyDispatchesChatEvent(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode
                    && instruction.getOpcode() == Opcodes.NEW
                    && CHAT_EVENT.equals(((TypeInsnNode) instruction).desc)) {
                return true;
            }
        }
        return false;
    }

    private InsnList buildDispatchInstructions(MethodNode method) {
        LabelNode continueLabel = new LabelNode();
        int messageLocal = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        InsnList instructions = new InsnList();
        instructions.add(new TypeInsnNode(Opcodes.NEW, CHAT_EVENT));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, messageLocal));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHAT_EVENT, "<init>", "(Ljava/lang/String;)V", false));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EVENT_BUS, "callEvent", "(L" + EVENT + ";)V", false));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CANCELLABLE_EVENT, "isCancelled", "()Z", false));
        instructions.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
        instructions.add(new InsnNode(Opcodes.RETURN));
        instructions.add(continueLabel);
        if ("(Ljava/lang/String;Z)V".equals(method.desc)) {
            instructions.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        return instructions;
    }
}

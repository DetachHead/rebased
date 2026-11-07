"""A dev-dist tool binary that every product shares."""

load(":dev_dist_plugin_descriptor.bzl", "dev_dist_neutral_product_transition")

def _dev_dist_tool_impl(ctx):
    tool = ctx.executable.tool

    # Under a directory of the target's name, so the executable keeps the tool's own basename.
    executable = ctx.actions.declare_file(ctx.label.name + "/" + tool.basename)
    ctx.actions.symlink(output = executable, target_file = tool, is_executable = True)
    return [DefaultInfo(executable = executable, files = depset([executable]))]

dev_dist_tool = rule(
    doc = """One tool binary for all products.

A rule reached in a product configuration names its tool with `cfg = "exec"`. The exec configuration keeps the product
flag, so each product compiled its own copy of the tool, and a worker tool started one process per product. This rule
resets the flag, so every product gets the same binary.""",
    implementation = _dev_dist_tool_impl,
    cfg = dev_dist_neutral_product_transition,
    executable = True,
    attrs = {
        "tool": attr.label(mandatory = True, executable = True, cfg = "target", doc = "The tool binary."),
        "_allowlist_function_transition": attr.label(default = Label("@bazel_tools//tools/allowlists/function_transition_allowlist")),
    },
)

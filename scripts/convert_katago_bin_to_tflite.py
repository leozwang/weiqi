#!/usr/bin/env python3
"""
Converts a trained KataGo model.bin.gz checkpoint into TFLite format (.tflite)
for execution on Google Tensor G4 TPU (Pixel 9 & 11).
"""

import os
import sys
import gzip
import numpy as np
import tensorflow as tf

def convert_katago_model(bin_gz_path: str, output_p11_path: str, output_p9_path: str):
    print(f"Reading trained KataGo model checkpoint from: {bin_gz_path}")
    with gzip.open(bin_gz_path, 'rb') as f:
        content = f.read()

    pos = 0

    def read_token():
        nonlocal pos
        while pos < len(content):
            while pos < len(content) and content[pos] in b' \t\r\n':
                pos += 1
            if pos >= len(content):
                return ''
            start = pos
            while pos < len(content) and content[pos] not in b' \t\r\n':
                pos += 1
            return content[start:pos].decode('ascii', errors='ignore')

    def read_floats(num_floats):
        nonlocal pos
        bin_idx = content.find(b'@BIN@', pos)
        if bin_idx == -1:
            raise ValueError('Could not find @BIN@ at pos ' + str(pos))
        data_start = bin_idx + 5
        data_bytes = num_floats * 4
        arr = np.frombuffer(content[data_start:data_start+data_bytes], dtype='<f4').copy()
        pos = data_start + data_bytes
        return arr

    def parse_conv():
        name = read_token()
        cy = int(read_token())
        cx = int(read_token())
        ic = int(read_token())
        oc = int(read_token())
        dy = int(read_token())
        dx = int(read_token())
        w = read_floats(cy * cx * ic * oc)
        # Reshape to Keras Conv2D format: (kernel_h, kernel_w, in_channels, out_channels)
        # KataGo format in file is (y, x, ic, oc)
        w_tensor = w.reshape((cy, cx, ic, oc))
        return name, (cy, cx, ic, oc), w_tensor

    def parse_bn():
        name = read_token()
        channels = int(read_token())
        epsilon = float(read_token())
        has_scale = int(read_token()) == 1
        has_bias = int(read_token()) == 1
        mean = read_floats(channels)
        var = read_floats(channels)
        scale = read_floats(channels) if has_scale else np.ones(channels, dtype=np.float32)
        bias = read_floats(channels) if has_bias else np.zeros(channels, dtype=np.float32)
        return name, channels, epsilon, mean, var, scale, bias

    def parse_act():
        name = read_token()
        return name

    def parse_matmul():
        name = read_token()
        ic = int(read_token())
        oc = int(read_token())
        w = read_floats(ic * oc)
        # Reshape to Keras Dense format: (in_channels, out_channels)
        w_tensor = w.reshape((ic, oc))
        return name, (ic, oc), w_tensor

    def parse_matbias():
        name = read_token()
        channels = int(read_token())
        b = read_floats(channels)
        return name, channels, b

    # ModelDesc Header
    model_name = read_token()
    model_version = int(read_token())
    num_input_spatial = int(read_token())
    num_input_global = int(read_token())

    # Trunk Header
    trunk_name = read_token()
    num_blocks = int(read_token())
    trunk_num_channels = int(read_token())
    mid_num_channels = int(read_token())
    regular_num_channels = int(read_token())
    dilated_num_channels = int(read_token())
    gpool_num_channels = int(read_token())

    print(f"Parsing KataGo Model v{model_version} ({model_name}): {num_blocks} blocks, {trunk_num_channels} channels...")

    init_conv_name, init_conv_shape, init_conv_w = parse_conv()
    init_mm_name, init_mm_shape, init_mm_w = parse_matmul()

    blocks_data = []
    for b in range(num_blocks):
        kind = read_token()
        name = read_token()
        if kind == 'ordinary_block':
            p_bn = parse_bn()
            p_act = parse_act()
            reg_conv = parse_conv()
            m_bn = parse_bn()
            m_act = parse_act()
            fin_conv = parse_conv()
            blocks_data.append(('ordinary', name, p_bn, reg_conv, m_bn, fin_conv))
        elif kind == 'gpool_block':
            p_bn = parse_bn()
            p_act = parse_act()
            reg_conv = parse_conv()
            g_conv = parse_conv()
            g_bn = parse_bn()
            g_act = parse_act()
            g_mul = parse_matmul()
            m_bn = parse_bn()
            m_act = parse_act()
            fin_conv = parse_conv()
            blocks_data.append(('gpool', name, p_bn, reg_conv, g_conv, g_bn, g_mul, m_bn, fin_conv))

    trunk_tip_bn = parse_bn()
    trunk_tip_act = parse_act()

    # Policy Head
    pol_name = read_token()
    p1_conv = parse_conv()
    g1_conv = parse_conv()
    g1_bn = parse_bn()
    g1_act = parse_act()
    gpool_to_bias_mul = parse_matmul()
    p1_bn = parse_bn()
    p1_act = parse_act()
    p2_conv = parse_conv()
    gpool_to_pass_mul = parse_matmul()

    # Value Head
    val_name = read_token()
    v1_conv = parse_conv()
    v1_bn = parse_bn()
    v1_act = parse_act()
    v2_mul = parse_matmul()
    v2_bias = parse_matbias()
    v2_act = parse_act()
    v3_mul = parse_matmul()
    v3_bias = parse_matbias()
    sv3_mul = parse_matmul()
    sv3_bias = parse_matbias()
    v_ownership_conv = parse_conv()

    print("Successfully parsed all binary weights! Constructing TensorFlow Functional Graph...")

    # Build Keras Model with Loaded Trained Weights
    spatial_input = tf.keras.Input(shape=(19, 19, 22), batch_size=1, name="spatial_input", dtype=tf.float32)
    global_input = tf.keras.Input(shape=(19,), batch_size=1, name="global_input", dtype=tf.float32)

    # Initial Conv
    l_init_conv = tf.keras.layers.Conv2D(trunk_num_channels, (5, 5), padding="same", use_bias=False, name="initial_conv")
    x_spatial = l_init_conv(spatial_input)
    l_init_conv.set_weights([init_conv_w])

    l_init_mm = tf.keras.layers.Dense(trunk_num_channels, use_bias=False, name="initial_matmul")
    x_global = l_init_mm(global_input)
    l_init_mm.set_weights([init_mm_w])

    # Broadcast global vector across spatial grid (19x19)
    x_global_reshaped = tf.keras.layers.Reshape((1, 1, trunk_num_channels))(x_global)
    x = tf.keras.layers.Add(name="initial_add")([x_spatial, x_global_reshaped])

    # Residual Blocks
    for b, b_data in enumerate(blocks_data):
        kind = b_data[0]
        res = x

        if kind == 'ordinary':
            _, name, p_bn, reg_conv, m_bn, fin_conv = b_data

            l_p_bn = tf.keras.layers.BatchNormalization(epsilon=p_bn[2], name=f"res_{b}_p_bn")
            x = l_p_bn(x)
            l_p_bn.set_weights([p_bn[5], p_bn[6], p_bn[3], p_bn[4]]) # [gamma, beta, mean, var]

            x = tf.keras.layers.ReLU(name=f"res_{b}_p_relu")(x)

            l_reg_conv = tf.keras.layers.Conv2D(reg_conv[1][3], (3, 3), padding="same", use_bias=False, name=f"res_{b}_reg_conv")
            x = l_reg_conv(x)
            l_reg_conv.set_weights([reg_conv[2]])

            l_m_bn = tf.keras.layers.BatchNormalization(epsilon=m_bn[2], name=f"res_{b}_m_bn")
            x = l_m_bn(x)
            l_m_bn.set_weights([m_bn[5], m_bn[6], m_bn[3], m_bn[4]])

            x = tf.keras.layers.ReLU(name=f"res_{b}_m_relu")(x)

            l_fin_conv = tf.keras.layers.Conv2D(fin_conv[1][3], (3, 3), padding="same", use_bias=False, name=f"res_{b}_fin_conv")
            x = l_fin_conv(x)
            l_fin_conv.set_weights([fin_conv[2]])

            x = tf.keras.layers.Add(name=f"res_{b}_add")([res, x])

        elif kind == 'gpool':
            _, name, p_bn, reg_conv, g_conv, g_bn, g_mul, m_bn, fin_conv = b_data

            l_p_bn = tf.keras.layers.BatchNormalization(epsilon=p_bn[2], name=f"res_{b}_p_bn")
            x = l_p_bn(x)
            l_p_bn.set_weights([p_bn[5], p_bn[6], p_bn[3], p_bn[4]])

            x = tf.keras.layers.ReLU(name=f"res_{b}_p_relu")(x)

            l_reg_conv = tf.keras.layers.Conv2D(reg_conv[1][3], (3, 3), padding="same", use_bias=False, name=f"res_{b}_reg_conv")
            x_reg = l_reg_conv(x)
            l_reg_conv.set_weights([reg_conv[2]])

            l_g_conv = tf.keras.layers.Conv2D(g_conv[1][3], (3, 3), padding="same", use_bias=False, name=f"res_{b}_g_conv")
            x_g = l_g_conv(x)
            l_g_conv.set_weights([g_conv[2]])

            l_g_bn = tf.keras.layers.BatchNormalization(epsilon=g_bn[2], name=f"res_{b}_g_bn")
            x_g = l_g_bn(x_g)
            l_g_bn.set_weights([g_bn[5], g_bn[6], g_bn[3], g_bn[4]])

            x_g = tf.keras.layers.ReLU(name=f"res_{b}_g_relu")(x_g)

            # Global pooling for residual block: [mean, mean * 0.5, max]
            g_mean = tf.keras.layers.GlobalAveragePooling2D(name=f"res_{b}_g_mean")(x_g)
            g_scaled = tf.keras.layers.Lambda(lambda t: t * 0.5, name=f"res_{b}_g_scaled")(g_mean)
            g_max = tf.keras.layers.GlobalMaxPooling2D(name=f"res_{b}_g_max")(x_g)
            g_concat = tf.keras.layers.Concatenate(name=f"res_{b}_g_concat")([g_mean, g_scaled, g_max])

            l_g_mul = tf.keras.layers.Dense(g_mul[1][1], use_bias=False, name=f"res_{b}_g_mul")
            g_bias = l_g_mul(g_concat)
            l_g_mul.set_weights([g_mul[2]])

            g_bias_reshaped = tf.keras.layers.Reshape((1, 1, g_mul[1][1]))(g_bias)
            x = tf.keras.layers.Add(name=f"res_{b}_g_add")([x_reg, g_bias_reshaped])

            l_m_bn = tf.keras.layers.BatchNormalization(epsilon=m_bn[2], name=f"res_{b}_m_bn")
            x = l_m_bn(x)
            l_m_bn.set_weights([m_bn[5], m_bn[6], m_bn[3], m_bn[4]])

            x = tf.keras.layers.ReLU(name=f"res_{b}_m_relu")(x)

            l_fin_conv = tf.keras.layers.Conv2D(fin_conv[1][3], (3, 3), padding="same", use_bias=False, name=f"res_{b}_fin_conv")
            x = l_fin_conv(x)
            l_fin_conv.set_weights([fin_conv[2]])

            x = tf.keras.layers.Add(name=f"res_{b}_add")([res, x])

    # Trunk Tip
    l_trunk_tip_bn = tf.keras.layers.BatchNormalization(epsilon=trunk_tip_bn[2], name="trunk_tip_bn")
    x = l_trunk_tip_bn(x)
    l_trunk_tip_bn.set_weights([trunk_tip_bn[5], trunk_tip_bn[6], trunk_tip_bn[3], trunk_tip_bn[4]])

    trunk_out = tf.keras.layers.ReLU(name="trunk_tip_relu")(x)

    # --- Policy Head ---
    l_p1_conv = tf.keras.layers.Conv2D(p1_conv[1][3], (1, 1), padding="same", use_bias=False, name="p1_conv")
    x_p1 = l_p1_conv(trunk_out)
    l_p1_conv.set_weights([p1_conv[2]])

    l_g1_conv = tf.keras.layers.Conv2D(g1_conv[1][3], (1, 1), padding="same", use_bias=False, name="g1_conv")
    x_g1 = l_g1_conv(trunk_out)
    l_g1_conv.set_weights([g1_conv[2]])

    l_g1_bn = tf.keras.layers.BatchNormalization(epsilon=g1_bn[2], name="g1_bn")
    x_g1 = l_g1_bn(x_g1)
    l_g1_bn.set_weights([g1_bn[5], g1_bn[6], g1_bn[3], g1_bn[4]])

    x_g1 = tf.keras.layers.ReLU(name="g1_relu")(x_g1)

    # Policy Head Global Pooling: [mean, mean * 0.5, max]
    pol_g_mean = tf.keras.layers.GlobalAveragePooling2D(name="pol_g_mean")(x_g1)
    pol_g_scaled = tf.keras.layers.Lambda(lambda t: t * 0.5, name="pol_g_scaled")(pol_g_mean)
    pol_g_max = tf.keras.layers.GlobalMaxPooling2D(name="pol_g_max")(x_g1)
    pol_g_concat = tf.keras.layers.Concatenate(name="pol_g_concat")([pol_g_mean, pol_g_scaled, pol_g_max])

    l_gpool_to_bias_mul = tf.keras.layers.Dense(gpool_to_bias_mul[1][1], use_bias=False, name="gpool_to_bias_mul")
    pol_g_bias = l_gpool_to_bias_mul(pol_g_concat)
    l_gpool_to_bias_mul.set_weights([gpool_to_bias_mul[2]])

    pol_g_bias_reshaped = tf.keras.layers.Reshape((1, 1, gpool_to_bias_mul[1][1]))(pol_g_bias)
    x_p1_biased = tf.keras.layers.Add(name="p1_bias_add")([x_p1, pol_g_bias_reshaped])

    l_p1_bn = tf.keras.layers.BatchNormalization(epsilon=p1_bn[2], name="p1_bn")
    x_p1_bn = l_p1_bn(x_p1_biased)
    l_p1_bn.set_weights([p1_bn[5], p1_bn[6], p1_bn[3], p1_bn[4]])

    x_p1_act = tf.keras.layers.ReLU(name="p1_relu")(x_p1_bn)

    l_p2_conv = tf.keras.layers.Conv2D(1, (1, 1), padding="same", use_bias=False, name="p2_conv")
    spatial_policy = l_p2_conv(x_p1_act)
    l_p2_conv.set_weights([p2_conv[2]])

    spatial_policy_flat = tf.keras.layers.Flatten(name="spatial_policy_flat")(spatial_policy)

    l_gpool_to_pass_mul = tf.keras.layers.Dense(1, use_bias=False, name="gpool_to_pass_mul")
    pass_logit = l_gpool_to_pass_mul(pol_g_concat)
    l_gpool_to_pass_mul.set_weights([gpool_to_pass_mul[2]])

    policy_output = tf.keras.layers.Concatenate(name="policy_output")([spatial_policy_flat, pass_logit])

    # --- Value Head ---
    l_v1_conv = tf.keras.layers.Conv2D(v1_conv[1][3], (1, 1), padding="same", use_bias=False, name="v1_conv")
    x_v1 = l_v1_conv(trunk_out)
    l_v1_conv.set_weights([v1_conv[2]])

    l_v1_bn = tf.keras.layers.BatchNormalization(epsilon=v1_bn[2], name="v1_bn")
    x_v1 = l_v1_bn(x_v1)
    l_v1_bn.set_weights([v1_bn[5], v1_bn[6], v1_bn[3], v1_bn[4]])

    x_v1 = tf.keras.layers.ReLU(name="v1_relu")(x_v1)

    # Value Head Global Pooling: [mean, mean * 0.5, mean * 0.15]
    val_g_mean = tf.keras.layers.GlobalAveragePooling2D(name="val_g_mean")(x_v1)
    val_g_scaled1 = tf.keras.layers.Lambda(lambda t: t * 0.5, name="val_g_scaled1")(val_g_mean)
    val_g_scaled2 = tf.keras.layers.Lambda(lambda t: t * 0.15, name="val_g_scaled2")(val_g_mean)
    val_g_concat = tf.keras.layers.Concatenate(name="val_g_concat")([val_g_mean, val_g_scaled1, val_g_scaled2])


    l_v2_mul = tf.keras.layers.Dense(v2_mul[1][1], use_bias=True, name="v2_mul")
    x_v2 = l_v2_mul(val_g_concat)
    l_v2_mul.set_weights([v2_mul[2], v2_bias[2]])

    x_v2 = tf.keras.layers.ReLU(name="v2_relu")(x_v2)

    l_v3_mul = tf.keras.layers.Dense(v3_mul[1][1], use_bias=True, name="v3_mul")
    win_logits = l_v3_mul(x_v2)
    l_v3_mul.set_weights([v3_mul[2], v3_bias[2]])

    l_sv3_mul = tf.keras.layers.Dense(sv3_mul[1][1], use_bias=True, name="sv3_mul")
    score_logits = l_sv3_mul(x_v2)
    l_sv3_mul.set_weights([sv3_mul[2], sv3_bias[2]])

    # Win/loss logits (3 channels: win, loss, noresult) + Score logits (4 channels: scoreMean, scoreMeanSq, lead, varTimeLeft)
    value_output = tf.keras.layers.Concatenate(name="value_output")([win_logits, score_logits])


    # --- Ownership Head ---
    l_v_ownership_conv = tf.keras.layers.Conv2D(1, (1, 1), padding="same", use_bias=True, name="ownership_conv")
    own_map = l_v_ownership_conv(x_v1)
    # v_ownership_conv in model file is ConvLayerDesc (no bias), so set weights
    l_v_ownership_conv.set_weights([v_ownership_conv[2], np.zeros((1,), dtype=np.float32)])

    ownership_output = tf.keras.layers.Flatten(name="ownership_output")(own_map)

    # Build Keras Functional Model
    keras_model = tf.keras.Model(
        inputs=[spatial_input, global_input],
        outputs=[policy_output, value_output, ownership_output],
        name="katago_trained_tpu"
    )

    print("Keras Model successfully constructed with trained weights! Converting to TFLite FP16...")

    converter = tf.lite.TFLiteConverter.from_keras_model(keras_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()

    os.makedirs(os.path.dirname(os.path.abspath(output_p11_path)), exist_ok=True)
    with open(output_p11_path, "wb") as f:
        f.write(tflite_model)
    with open(output_p9_path, "wb") as f:
        f.write(tflite_model)

    print(f"Successfully generated trained TFLite TPU model at: {output_p11_path} ({len(tflite_model)} bytes)")
    return True

if __name__ == "__main__":
    convert_katago_model(
        "src/assets/model.bin.gz",
        "src/assets/model_tpu_p11.tflite",
        "src/assets/model_tpu_p9.tflite"
    )

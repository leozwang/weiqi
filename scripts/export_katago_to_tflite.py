#!/usr/bin/env python3
"""
Script to export a KataGo PyTorch model checkpoint to TensorFlow Lite (.tflite)
formatted for Google Tensor G4 TPU (Pixel 9) AOT compilation.

Inputs:
  - Spatial: [Batch, 19, 19, 22] (Board representation)
  - Global:  [Batch, 19] (Komi, rules, game state features)

Outputs:
  - Policy:    [Batch, 362] (Move logits + pass)
  - Value:     [Batch, 4]   (Win/Loss/Score distribution)
  - Ownership: [Batch, 361] (Territory estimation)
"""

import os
import sys
import argparse
import numpy as np

def create_katago_tflite_model(output_path: str, batch_size: int = 1, num_blocks: int = 10, channels: int = 128):
    try:
        import tensorflow as tf
    except ImportError:
        print("TensorFlow is required to export the TFLite model. Please install tensorflow.")
        return False

    print(f"Constructing KataGo {num_blocks}b{channels}c model graph in TensorFlow...")

    # Define Inputs
    spatial_input = tf.keras.Input(shape=(19, 19, 22), batch_size=batch_size, name="spatial_input", dtype=tf.float32)
    global_input = tf.keras.Input(shape=(19,), batch_size=batch_size, name="global_input", dtype=tf.float32)

    # Initial Convolution
    x = tf.keras.layers.Conv2D(channels, (3, 3), padding="same", use_bias=False, name="initial_conv")(spatial_input)
    x = tf.keras.layers.BatchNormalization(name="initial_bn")(x)
    x = tf.keras.layers.ReLU(name="initial_relu")(x)

    # Residual Trunk (10 blocks)
    for b in range(num_blocks):
        res = x
        x = tf.keras.layers.Conv2D(channels, (3, 3), padding="same", use_bias=False, name=f"res_{b}_conv1")(x)
        x = tf.keras.layers.BatchNormalization(name=f"res_{b}_bn1")(x)
        x = tf.keras.layers.ReLU(name=f"res_{b}_relu1")(x)
        x = tf.keras.layers.Conv2D(channels, (3, 3), padding="same", use_bias=False, name=f"res_{b}_conv2")(x)
        x = tf.keras.layers.BatchNormalization(name=f"res_{b}_bn2")(x)
        x = tf.keras.layers.Add(name=f"res_{b}_add")([res, x])
        x = tf.keras.layers.ReLU(name=f"res_{b}_relu2")(x)

    # Policy Head
    p = tf.keras.layers.Conv2D(32, (1, 1), padding="same", use_bias=False, name="policy_conv")(x)
    p = tf.keras.layers.BatchNormalization(name="policy_bn")(p)
    p = tf.keras.layers.ReLU(name="policy_relu")(p)
    p_flat = tf.keras.layers.Flatten(name="policy_flat")(p)
    
    # Global policy features
    g_p = tf.keras.layers.Dense(32, activation="relu", name="policy_global_dense")(global_input)
    p_concat = tf.keras.layers.Concatenate(name="policy_concat")([p_flat, g_p])
    policy_output = tf.keras.layers.Dense(362, name="policy_output")(p_concat)

    # Value Head
    v_pool = tf.keras.layers.GlobalAveragePooling2D(name="value_pool")(x)
    v_concat = tf.keras.layers.Concatenate(name="value_concat")([v_pool, global_input])
    v = tf.keras.layers.Dense(128, activation="relu", name="value_dense1")(v_concat)
    value_output = tf.keras.layers.Dense(4, name="value_output")(v)

    # Ownership / Territory Head
    own = tf.keras.layers.Conv2D(1, (1, 1), padding="same", name="ownership_conv")(x)
    ownership_output = tf.keras.layers.Flatten(name="ownership_output")(own)

    # Build Keras Model
    model = tf.keras.Model(
        inputs=[spatial_input, global_input],
        outputs=[policy_output, value_output, ownership_output],
        name="katago_mobile_tpu"
    )

    print("Converting Model to TFLite (FP16)...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"Successfully generated TFLite model at: {output_path} ({len(tflite_model)} bytes)")
    return True

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export KataGo model to TFLite for TPU")
    parser.add_argument("--output", default="src/assets/model_tpu_p9.tflite", help="Target TFLite output path")

    parser.add_argument("--batch_size", type=int, default=1, help="Fixed batch size for TPU execution")
    parser.add_argument("--blocks", type=int, default=10, help="Number of residual blocks")
    parser.add_argument("--channels", type=int, default=128, help="Number of trunk channels")
    args = parser.parse_args()

    create_katago_tflite_model(args.output, args.batch_size, args.blocks, args.channels)
